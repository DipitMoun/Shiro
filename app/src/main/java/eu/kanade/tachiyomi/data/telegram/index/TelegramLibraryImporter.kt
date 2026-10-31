package eu.kanade.tachiyomi.data.telegram.index

import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.data.collection.CollectionType
import eu.kanade.tachiyomi.data.telegram.TelegramFileHeuristics
import eu.kanade.tachiyomi.data.telegram.TelegramUrls
import eu.kanade.tachiyomi.data.telegram.index.aggregationKey
import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWork
import eu.kanade.tachiyomi.data.telegram.source.TelegramAnimeSource
import eu.kanade.tachiyomi.data.telegram.source.TelegramMangaSource
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.anime.interactor.CreateAnimeCategoryWithName
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.CreateMangaCategoryWithName
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Importe le contenu d'un canal Telegram dans la bibliothèque.
 *
 * Un canal devient une collection à la racine. Les œuvres non reconnues sont
 * regroupées dans une œuvre synthétique par canal, elle-même rangée dans la
 * collection « Unknown ».
 */
class TelegramLibraryImporter(
    private val store: TelegramIndexStore,
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val createAnimeCategory: CreateAnimeCategoryWithName = Injekt.get(),
    private val getMangaCategories: GetMangaCategories = Injekt.get(),
    private val createMangaCategory: CreateMangaCategoryWithName = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
) {

    private val metadataManager: CollectionMetadataManager by injectLazy()

    suspend fun importChannel(
        chatId: Long,
        includeUnknown: Boolean = true,
        includeDocuments: Boolean = true,
    ): TelegramImportResult {
        val channel = store.channel(chatId)
            ?: throw IllegalStateException("Canal non synchronisé")

        // Toutes les œuvres Telegram vont dans une seule collection « Telegram ».
        val categoryName = TELEGRAM_CATEGORY

        val animeWorks = channel.videoWorks
        val mangaWorks = if (includeDocuments) channel.documentWorks else emptyList()

        val resolvedAnime = animeWorks.filterNot { it.isUnresolved }
        val resolvedManga = mangaWorks.filterNot { it.isUnresolved }
        val unknownAnime = includeUnknown && animeWorks.any { it.isUnresolved }
        val unknownManga = includeUnknown && mangaWorks.any { it.isUnresolved }

        var importedAnime = 0
        var importedManga = 0
        var skipped = 0

        if (resolvedAnime.isNotEmpty() || unknownAnime) {
            val categoryId = ensureAnimeCategory(categoryName)
            resolvedAnime.forEach { work ->
                val url = TelegramUrls.aggregatedWork(work.aggregationKey())
                val existing = animeRepository.getAnimeByUrlAndSourceId(url, TelegramAnimeSource.ID)
                if (existing != null) {
                    animeRepository.updateAnime(AnimeUpdate(id = existing.id, favorite = true))
                    animeRepository.setAnimeCategories(existing.id, listOf(categoryId))
                    skipped++
                    return@forEach
                }
                val inserted = animeRepository.insertAnime(work.toAnime(url))
                if (inserted != null) {
                    animeRepository.setAnimeCategories(inserted, listOf(categoryId))
                    importedAnime++
                } else {
                    skipped++
                }
            }

            if (unknownAnime) {
                val unknownCategoryId = ensureAnimeCategory(categoryName)
                val url = TelegramUrls.work(chatId, UNKNOWN_SLUG, null)
                val existing = animeRepository.getAnimeByUrlAndSourceId(url, TelegramAnimeSource.ID)
                if (existing != null) {
                    animeRepository.setAnimeCategories(existing.id, listOf(unknownCategoryId))
                } else {
                    val synthetic = unknownAnimeWork(channel)
                    val inserted = animeRepository.insertAnime(synthetic.toAnime(url))
                    if (inserted != null) {
                        animeRepository.setAnimeCategories(inserted, listOf(unknownCategoryId))
                        importedAnime++
                    }
                }
            }
        }

        if (resolvedManga.isNotEmpty() || unknownManga) {
            val categoryId = ensureMangaCategory(categoryName)
            resolvedManga.forEach { work ->
                val url = work.toMangaUrl()
                if (mangaRepository.getMangaByUrlAndSourceId(url, TelegramMangaSource.ID) != null) {
                    mangaRepository.getMangaByUrlAndSourceId(url, TelegramMangaSource.ID)
                        ?.let { mangaRepository.setMangaCategories(it.id, listOf(categoryId)) }
                    skipped++
                    return@forEach
                }
                val inserted = mangaRepository.insertManga(work.toManga(url))
                if (inserted != null) {
                    mangaRepository.setMangaCategories(inserted, listOf(categoryId))
                    importedManga++
                } else {
                    skipped++
                }
            }

            if (unknownManga) {
                val unknownCategoryId = ensureMangaCategory(categoryName)
                val url = TelegramUrls.manga(chatId, UNKNOWN_SLUG)
                val existing = mangaRepository.getMangaByUrlAndSourceId(url, TelegramMangaSource.ID)
                if (existing != null) {
                    mangaRepository.setMangaCategories(existing.id, listOf(unknownCategoryId))
                } else {
                    val synthetic = unknownMangaWork(channel)
                    val inserted = mangaRepository.insertManga(synthetic.toManga(url))
                    if (inserted != null) {
                        mangaRepository.setMangaCategories(inserted, listOf(unknownCategoryId))
                        importedManga++
                    }
                }
            }
        }

        return TelegramImportResult(
            categoryName = categoryName,
            importedAnime = importedAnime,
            importedManga = importedManga,
            unknownAnime = unknownAnime,
            unknownManga = unknownManga,
            skipped = skipped,
        )
    }

    // ------------------------------------------------------------------
    // Œuvre validée manuellement
    // ------------------------------------------------------------------

    /**
     * Ajoute une œuvre validée à la bibliothèque. Retourne l'identifiant de
     * l'entrée de bibliothèque (animé ou manga), ou `null` en cas d'échec.
     */
    suspend fun importSavedWork(work: TelegramSavedWork): Long? {
        val categoryName = TELEGRAM_CATEGORY
        val now = System.currentTimeMillis()

        if (work.kind == TelegramSavedKind.VIDEO) {
            val url = TelegramUrls.savedAnime(work.id)
            val categoryId = ensureAnimeCategory(categoryName)
            val existing = animeRepository.getAnimeByUrlAndSourceId(url, TelegramAnimeSource.ID)
            if (existing != null) {
                animeRepository.setAnimeCategories(existing.id, listOf(categoryId))
                return existing.id
            }

            val inserted = animeRepository.insertAnime(
                Anime.create().copy(
                    source = TelegramAnimeSource.ID,
                    url = url,
                    title = work.title,
                    favorite = true,
                    dateAdded = now,
                    lastUpdate = now,
                    nextUpdate = now + DAY_MS,
                    description = "${work.files.size} fichier(s) Telegram.",
                    initialized = false,
                    lastModifiedAt = now,
                    favoriteModifiedAt = now,
                ),
            )
            if (inserted != null) {
                animeRepository.setAnimeCategories(inserted, listOf(categoryId))
            }
            return inserted
        }

        val url = TelegramUrls.savedManga(work.id)
        val categoryId = ensureMangaCategory(categoryName)
        val existing = mangaRepository.getMangaByUrlAndSourceId(url, TelegramMangaSource.ID)
        if (existing != null) {
            mangaRepository.setMangaCategories(existing.id, listOf(categoryId))
            return existing.id
        }

        val inserted = mangaRepository.insertManga(
            Manga.create().copy(
                source = TelegramMangaSource.ID,
                url = url,
                title = work.title,
                favorite = true,
                dateAdded = now,
                lastUpdate = now,
                nextUpdate = now + DAY_MS,
                description = "${work.files.size} fichier(s) Telegram.",
                initialized = false,
                lastModifiedAt = now,
                favoriteModifiedAt = now,
            ),
        )
        if (inserted != null) {
            mangaRepository.setMangaCategories(inserted, listOf(categoryId))
        }
        return inserted
    }

    /**
     * Crée (ou retrouve) l'entrée technique d'une œuvre agrégée multi-canaux.
     * Elle reste hors bibliothèque (`favorite = false`) tant que l'utilisateur
     * n'utilise pas le cœur de la fiche AnimeScreen.
     */
    suspend fun ensureAggregatedWork(
        workKey: String,
        title: String,
        coverUrl: String?,
        resolved: TelegramResolvedWork? = null,
    ): Long? {
        val url = TelegramUrls.aggregatedWork(workKey)
        val existing = animeRepository.getAnimeByUrlAndSourceId(url, TelegramAnimeSource.ID)
        val now = System.currentTimeMillis()
        if (existing != null) {
            animeRepository.updateAnime(
                AnimeUpdate(
                    id = existing.id,
                    title = resolved?.title?.takeIf { it.isNotBlank() } ?: title,
                    description = resolved?.description?.takeIf { it.isNotBlank() },
                    thumbnailUrl = resolved?.coverUrl?.takeIf { it.isNotBlank() } ?: coverUrl,
                    genre = resolved?.genres?.takeIf { it.isNotEmpty() },
                    status = resolved?.status?.let { it.toAnimeStatus() },
                    initialized = false,
                    lastUpdate = now,
                    nextUpdate = now + DAY_MS,
                ),
            )
            return existing.id
        }

        return animeRepository.insertAnime(
            Anime.create().copy(
                source = TelegramAnimeSource.ID,
                url = url,
                title = resolved?.title?.takeIf { it.isNotBlank() } ?: title,
                description = resolved?.description?.takeIf { it.isNotBlank() },
                thumbnailUrl = resolved?.coverUrl?.takeIf { it.isNotBlank() } ?: coverUrl,
                genre = resolved?.genres?.takeIf { it.isNotEmpty() },
                status = resolved?.status?.toAnimeStatus() ?: 0L,
                favorite = false,
                dateAdded = now,
                lastUpdate = now,
                nextUpdate = now + DAY_MS,
                initialized = false,
                lastModifiedAt = now,
            ),
        )
    }

    /**
     * Met à jour une fiche agrégée déjà ouverte après une reconnaissance
     * manuelle. `initialized = false` force AnimeScreen à relire les détails
     * complets depuis TelegramAnimeSource.
     */
    suspend fun updateAggregatedWorkMetadata(
        workKey: String,
        resolved: TelegramResolvedWork,
    ) {
        val url = TelegramUrls.aggregatedWork(workKey)
        val anime = animeRepository.getAnimeByUrlAndSourceId(url, TelegramAnimeSource.ID) ?: return
        val now = System.currentTimeMillis()
        animeRepository.updateAnime(
            AnimeUpdate(
                id = anime.id,
                title = resolved.title?.takeIf { it.isNotBlank() },
                description = resolved.description?.takeIf { it.isNotBlank() },
                thumbnailUrl = resolved.coverUrl?.takeIf { it.isNotBlank() },
                genre = resolved.genres.takeIf { it.isNotEmpty() },
                status = resolved.status?.let { it.toAnimeStatus() },
                initialized = false,
                lastUpdate = now,
                nextUpdate = now + DAY_MS,
            ),
        )
    }

    /** Retrouve l'entrée de bibliothèque associée à une œuvre validée. */
    suspend fun libraryIdOf(work: TelegramSavedWork): Long? {
        return if (work.kind == TelegramSavedKind.VIDEO) {
            animeRepository.getAnimeByUrlAndSourceId(
                TelegramUrls.savedAnime(work.id),
                TelegramAnimeSource.ID,
            )?.id
        } else {
            mangaRepository.getMangaByUrlAndSourceId(
                TelegramUrls.savedManga(work.id),
                TelegramMangaSource.ID,
            )?.id
        }
    }

    /**
     * Met à jour le titre, les métadonnées AniList et, si demandé, la liste
     * d'épisodes/chapitres de l'entrée de bibliothèque.
     */
    suspend fun updateSavedWorkEntry(work: TelegramSavedWork, resetInitialized: Boolean = false) {
        val now = System.currentTimeMillis()
        val resolved = work.resolution

        if (work.kind == TelegramSavedKind.VIDEO) {
            val anime = animeRepository.getAnimeByUrlAndSourceId(
                TelegramUrls.savedAnime(work.id),
                TelegramAnimeSource.ID,
            ) ?: return

            animeRepository.updateAnime(
                AnimeUpdate(
                    id = anime.id,
                    title = work.title,
                    description = resolved?.description?.takeIf { it.isNotBlank() },
                    thumbnailUrl = resolved?.coverUrl,
                    genre = resolved?.genres?.takeIf { it.isNotEmpty() },
                    status = resolved?.status?.let { it.toAnimeStatus() },
                    initialized = if (resetInitialized) false else null,
                    lastUpdate = if (resetInitialized) now else null,
                    nextUpdate = if (resetInitialized) now else null,
                    favorite = true,
                ),
            )
            return
        }

        val manga = mangaRepository.getMangaByUrlAndSourceId(
            TelegramUrls.savedManga(work.id),
            TelegramMangaSource.ID,
        ) ?: return

        mangaRepository.updateManga(
            MangaUpdate(
                id = manga.id,
                title = work.title,
                description = resolved?.description?.takeIf { it.isNotBlank() },
                thumbnailUrl = resolved?.coverUrl,
                genre = resolved?.genres?.takeIf { it.isNotEmpty() },
                status = resolved?.status?.let { it.toMangaStatus() },
                initialized = if (resetInitialized) false else null,
                lastUpdate = if (resetInitialized) now else null,
                nextUpdate = if (resetInitialized) now else null,
                favorite = true,
            ),
        )
    }

    /** Retire l'œuvre de la bibliothèque (l'entrée de base reste en cache). */
    suspend fun deleteSavedWorkEntry(work: TelegramSavedWork) {
        if (work.kind == TelegramSavedKind.VIDEO) {
            val anime = animeRepository.getAnimeByUrlAndSourceId(
                TelegramUrls.savedAnime(work.id),
                TelegramAnimeSource.ID,
            ) ?: return
            animeRepository.updateAnime(AnimeUpdate(id = anime.id, favorite = false))
            return
        }

        val manga = mangaRepository.getMangaByUrlAndSourceId(
            TelegramUrls.savedManga(work.id),
            TelegramMangaSource.ID,
        ) ?: return
        mangaRepository.updateManga(MangaUpdate(id = manga.id, favorite = false))
    }

    // ------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------

    /** Rassemble toutes les entrées Telegram existantes dans « Telegram ». */
    suspend fun migrateToTelegramCategory() {
        val animeCategoryId = ensureAnimeCategory(TELEGRAM_CATEGORY)
        animeRepository.getAnimeFavoritesBySourceId(TelegramAnimeSource.ID)
            .first()
            .forEach { animeRepository.setAnimeCategories(it.id, listOf(animeCategoryId)) }

        val mangaCategoryId = ensureMangaCategory(TELEGRAM_CATEGORY)
        mangaRepository.getMangaFavoritesBySourceId(TelegramMangaSource.ID)
            .first()
            .forEach { mangaRepository.setMangaCategories(it.id, listOf(mangaCategoryId)) }
    }

    /** Retire les entrées dont le titre est un nom d'export (`VID_...`, `YouCut_...`). */
    suspend fun removeGenericWorks() {
        animeRepository.getAnimeFavoritesBySourceId(TelegramAnimeSource.ID)
            .first()
            .filter { TelegramFileHeuristics.isGenericTitle(it.title) }
            .forEach { animeRepository.updateAnime(AnimeUpdate(id = it.id, favorite = false)) }

        mangaRepository.getMangaFavoritesBySourceId(TelegramMangaSource.ID)
            .first()
            .filter { TelegramFileHeuristics.isGenericTitle(it.title) }
            .forEach { mangaRepository.updateManga(MangaUpdate(id = it.id, favorite = false)) }
    }

    /**
     * Force le rechargement des listes d'épisodes : les anciennes URLs ne
     * contenaient que le fileId (instable) et pouvaient ouvrir un autre post.
     */
    suspend fun refreshTelegramEpisodeLists() {
        animeRepository.getAnimeFavoritesBySourceId(TelegramAnimeSource.ID)
            .first()
            .forEach { animeRepository.updateAnime(AnimeUpdate(id = it.id, initialized = false)) }

        mangaRepository.getMangaFavoritesBySourceId(TelegramMangaSource.ID)
            .first()
            .forEach { mangaRepository.updateManga(MangaUpdate(id = it.id, initialized = false)) }
    }

    private suspend fun ensureAnimeCategory(name: String): Long {
        val categories = getAnimeCategories.await()
        val existing = categories.firstOrNull { it.parentId == null && it.name.equals(name, true) }
            ?.id
        val categoryId = existing ?: when (val result = createAnimeCategory.await(name)) {
            is CreateAnimeCategoryWithName.Result.Success -> {
                getAnimeCategories.await()
                    .firstOrNull { it.parentId == null && it.name.equals(name, true) }
                    ?.id
            }
            is CreateAnimeCategoryWithName.Result.InternalError -> {
                logcat(LogPriority.ERROR, result.error) { "Création de la collection $name impossible" }
                null
            }
        } ?: throw IllegalStateException("Collection $name introuvable")

        metadataManager.ensureTelegramBanner(CollectionType.ANIME, categoryId)
        return categoryId
    }

    private suspend fun ensureMangaCategory(name: String): Long {
        val categories = getMangaCategories.await()
        val existing = categories.firstOrNull { it.parentId == null && it.name.equals(name, true) }
            ?.id
        val categoryId = existing ?: when (val result = createMangaCategory.await(name)) {
            is CreateMangaCategoryWithName.Result.Success -> {
                getMangaCategories.await()
                    .firstOrNull { it.parentId == null && it.name.equals(name, true) }
                    ?.id
            }
            is CreateMangaCategoryWithName.Result.InternalError -> {
                logcat(LogPriority.ERROR, result.error) { "Création de la collection $name impossible" }
                null
            }
        } ?: throw IllegalStateException("Collection $name introuvable")

        metadataManager.ensureTelegramBanner(CollectionType.MANGA, categoryId)
        return categoryId
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    private fun TelegramWorkEntry.toAnimeUrl(): String {
        val slug = id.split(':').getOrNull(1) ?: TelegramTitleParser.slug(baseTitle)
        return TelegramUrls.work(chatId, slug, season)
    }

    private fun TelegramWorkEntry.toMangaUrl(): String {
        val slug = id.split(':').getOrNull(1) ?: TelegramTitleParser.slug(baseTitle)
        return TelegramUrls.manga(chatId, slug)
    }

    private fun TelegramWorkEntry.toAnime(url: String): Anime {
        val now = System.currentTimeMillis()
        return Anime.create().copy(
            source = TelegramAnimeSource.ID,
            url = url,
            title = displayTitle,
            favorite = true,
            dateAdded = now,
            lastUpdate = now,
            nextUpdate = now + DAY_MS,
            thumbnailUrl = coverUrl,
            description = description,
            genre = genres.takeIf { it.isNotEmpty() },
            status = status.toAnimeStatus(),
            initialized = false,
            lastModifiedAt = now,
            favoriteModifiedAt = now,
        )
    }

    private fun TelegramWorkEntry.toManga(url: String): Manga {
        val now = System.currentTimeMillis()
        return Manga.create().copy(
            source = TelegramMangaSource.ID,
            url = url,
            title = displayTitle,
            favorite = true,
            dateAdded = now,
            lastUpdate = now,
            nextUpdate = now + DAY_MS,
            thumbnailUrl = coverUrl,
            description = description,
            genre = genres.takeIf { it.isNotEmpty() },
            status = status.toMangaStatus(),
            initialized = false,
            lastModifiedAt = now,
            favoriteModifiedAt = now,
        )
    }

    private fun unknownAnimeWork(channel: TelegramChannelIndex): TelegramWorkEntry {
        val cleaned = TelegramTitleParser.cleanChannelName(channel.title).ifBlank { channel.title }
        val unresolved = channel.videoWorks.filter { it.isUnresolved }
        return TelegramWorkEntry(
            id = "${channel.chatId}:$UNKNOWN_SLUG:0",
            chatId = channel.chatId,
            mediaKind = TelegramMediaKind.VIDEO,
            title = "Non reconnus — $cleaned",
            baseTitle = "Non reconnus — $cleaned",
            fileCount = unresolved.sumOf { it.fileCount },
            episodeCount = unresolved.sumOf { it.episodeCount },
            lastFileDate = unresolved.maxOfOrNull { it.lastFileDate } ?: 0L,
        )
    }

    private fun unknownMangaWork(channel: TelegramChannelIndex): TelegramWorkEntry {
        val cleaned = TelegramTitleParser.cleanChannelName(channel.title).ifBlank { channel.title }
        val unresolved = channel.documentWorks.filter { it.isUnresolved }
        return TelegramWorkEntry(
            id = "${channel.chatId}:$UNKNOWN_SLUG:0",
            chatId = channel.chatId,
            mediaKind = TelegramMediaKind.DOCUMENT,
            title = "Non reconnus — $cleaned",
            baseTitle = "Non reconnus — $cleaned",
            fileCount = unresolved.sumOf { it.fileCount },
            episodeCount = unresolved.sumOf { it.episodeCount },
            lastFileDate = unresolved.maxOfOrNull { it.lastFileDate } ?: 0L,
        )
    }

    private fun String?.toAnimeStatus(): Long = when (this?.uppercase()) {
        "FINISHED" -> 2L
        "RELEASING" -> 1L
        "CANCELLED" -> 5L
        "HIATUS" -> 6L
        "NOT_YET_RELEASED" -> 7L
        else -> 0L
    }

    private fun String?.toMangaStatus(): Long = when (this?.uppercase()) {
        "FINISHED" -> 2L
        "RELEASING" -> 1L
        "CANCELLED" -> 5L
        "HIATUS" -> 6L
        "NOT_YET_RELEASED" -> 0L
        else -> 0L
    }

    companion object {
        const val UNKNOWN_SLUG = "__unknown__"
        const val TELEGRAM_CATEGORY = "Telegram"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

val TelegramWorkEntry.isUnresolved: Boolean
    get() = anilistId == null && matchScore >= 0.0

data class TelegramImportResult(
    val categoryName: String,
    val importedAnime: Int,
    val importedManga: Int,
    val unknownAnime: Boolean,
    val unknownManga: Boolean,
    val skipped: Int,
) {
    val imported: Int
        get() = importedAnime + importedManga
}
