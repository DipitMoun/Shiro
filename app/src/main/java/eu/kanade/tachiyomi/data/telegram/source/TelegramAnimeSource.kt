package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.media.EpisodeMediaValidator
import eu.kanade.tachiyomi.data.telegram.TelegramFileHeuristics
import eu.kanade.tachiyomi.data.telegram.TelegramUrls
import eu.kanade.tachiyomi.data.telegram.index.TelegramChannelIndex
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexStore
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexedFile
import eu.kanade.tachiyomi.data.telegram.index.TelegramLibraryImporter
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkEntry
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkOverrideStore
import eu.kanade.tachiyomi.data.telegram.index.aggregationKey
import eu.kanade.tachiyomi.data.telegram.index.identityKey
import eu.kanade.tachiyomi.data.telegram.index.isUnresolved
import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedFile
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWork
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.Locale

/**
 * Source vidéo Telegram.
 *
 * Elle ne fait aucun appel réseau : les œuvres, épisodes et variantes sont lus
 * dans l'index local produit par [eu.kanade.tachiyomi.data.telegram.index.TelegramIndexManager].
 * Les fichiers sont récupérés par TDLib au moment du téléchargement.
 */
class TelegramAnimeSource : AnimeHttpSource() {

    override val id: Long = ID
    override val name: String = "[Telegram] Vidéo"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val store: TelegramIndexStore by injectLazy()
    private val savedStore: TelegramSavedWorkStore by injectLazy()
    private val overrides: TelegramWorkOverrideStore by injectLazy()

    // ------------------------------------------------------------------
    // Catalogue
    // ------------------------------------------------------------------

    override fun getFilterList(): AnimeFilterList {
        val channels = channels()
        return AnimeFilterList(TelegramChannelFilter(channels))
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val entries = buildEntries(channelId = null)
            .sortedBy { it.anime.title.lowercase(Locale.ROOT) }
        return paginate(entries, page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val entries = buildEntries(channelId = null)
            .sortedByDescending { it.lastDate }
        return paginate(entries, page)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val channelId = filters.filterIsInstance<TelegramChannelFilter>()
            .firstOrNull()
            ?.selectedChatId(channels())

        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val entries = buildEntries(channelId)
            .filter { entry ->
                normalizedQuery.isEmpty() ||
                    entry.anime.title.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            .sortedBy { it.anime.title.lowercase(Locale.ROOT) }

        return paginate(entries, page)
    }

    // ------------------------------------------------------------------
    // Détails
    // ------------------------------------------------------------------

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        TelegramUrls.parseSaved(anime.url)?.let { ref ->
            if (ref.manga) return anime
            val work = savedStore.find(ref.id) ?: return anime
            return work.toSAnime().apply {
                url = anime.url
                initialized = true
            }
        }

        TelegramUrls.parseAggregatedWork(anime.url)?.let { workKey ->
            val (channel, work) = worksFor(workKey).firstOrNull() ?: return anime
            return work.toSAnime(channel).apply {
                url = anime.url
                initialized = true
            }
        }

        val ref = TelegramUrls.parseWork(anime.url) ?: return anime
        val channel = store.channel(ref.chatId) ?: return anime

        val resolved = if (ref.slug == TelegramLibraryImporter.UNKNOWN_SLUG) {
            unknownAnime(channel)
        } else {
            channel.videoWorks
                .firstOrNull { it.id == workId(ref.chatId, ref.slug, ref.season) }
                ?.toSAnime(channel)
                ?: anime
        }

        return resolved.apply {
            url = anime.url
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        TelegramUrls.parseSaved(anime.url)?.let { ref ->
            if (ref.manga) return emptyList()
            val work = savedStore.find(ref.id) ?: return emptyList()
            return savedEpisodes(work)
        }

        TelegramUrls.parseAggregatedWork(anime.url)?.let { workKey ->
            return aggregatedEpisodes(workKey)
        }

        val ref = TelegramUrls.parseWork(anime.url) ?: return emptyList()
        val channel = store.channel(ref.chatId) ?: return emptyList()

        val files = filesFor(channel, ref.chatId, ref.slug, ref.season)
        if (files.isEmpty()) return emptyList()

        val episodes = mutableListOf<SEpisode>()

        files.filter { it.episode != null }
            .groupBy { it.episode!! }
            .forEach { (number, group) ->
                val end = group.maxOfOrNull { it.episodeEnd ?: number } ?: number
                val name = if (end > number) "Épisodes $number-$end" else "Épisode $number"

                episodes += SEpisode.create().apply {
                    url = TelegramUrls.episode(ref.chatId, ref.slug, ref.season, number)
                    this.name = name
                    episode_number = number.toFloat()
                    date_upload = (group.maxOfOrNull { it.date } ?: 0L) * 1000
                    summary = group.map { it.displayLabel }.distinct().joinToString(", ")
                        .takeIf { it.isNotBlank() }
                }
            }

        // Vidéos sans numéro : une entrée par fichier, l'URL pointe le fichier.
        files.filter { it.episode == null }
            .sortedBy { it.date }
            .forEach { file ->
                episodes += SEpisode.create().apply {
                    url = TelegramUrls.file(
                        file.fileId,
                        file.sizeBytes,
                        file.chatId,
                        extensionOf(file.fileName),
                        messageId = file.messageId,
                    )
                    name = file.title.ifBlank { file.fileName }
                    episode_number = -1f
                    date_upload = file.date * 1000
                    summary = file.displayLabel
                }
            }

        return episodes.sortedWith(
            compareBy<SEpisode> { it.episode_number < 0 }.thenBy { it.episode_number },
        )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        TelegramUrls.parseSavedUnit(episode.url)?.let { ref ->
            val work = savedStore.find(ref.id) ?: return emptyList()
            return work.files
                .filterNot {
                    TelegramFileHeuristics.isSuspect(it.fileName, it.caption, work.title) ||
                        !TelegramFileHeuristics.isVideoFile(it.fileName, it.mimeType) ||
                        EpisodeMediaValidator.isTooSmall(it.sizeBytes)
                }
                .filter { it.season == ref.season && it.episode == ref.number }
                .sortedBy { it.sizeBytes }
                .map { it.toVideo() }
        }

        TelegramUrls.parseSavedFile(episode.url)?.let { ref ->
            val work = savedStore.find(ref.id) ?: return emptyList()
            return work.files
                .filterNot {
                    TelegramFileHeuristics.isSuspect(it.fileName, it.caption, work.title) ||
                        !TelegramFileHeuristics.isVideoFile(it.fileName, it.mimeType) ||
                        EpisodeMediaValidator.isTooSmall(it.sizeBytes)
                }
                .filter { it.messageId == ref.messageId }
                .map { it.toVideo() }
        }

        TelegramUrls.parseAggregatedEpisode(episode.url)?.let { ref ->
            return aggregatedFiles(ref.workKey)
                .filter { (it.first.season ?: 1) == (ref.season ?: 1) && it.first.episode == ref.number }
                .sortedBy { it.first.sizeBytes }
                .map { (file, channelTitle) -> file.toVideo(channelTitle) }
        }

        TelegramUrls.parseEpisode(episode.url)?.let { ref ->
            val channel = store.channel(ref.chatId) ?: return emptyList()
            val files = filesFor(channel, ref.chatId, ref.slug, ref.season)
                .filter { it.episode == ref.number }
            return files.sortedBy { it.sizeBytes }.map { it.toVideo() }
        }

        TelegramUrls.parseFile(episode.url)?.let { ref ->
            val channel = ref.chatId?.let { store.channel(it) } ?: return emptyList()
            return channel.videoFiles
                .filter {
                    TelegramFileHeuristics.isVideoFile(it.fileName, it.mimeType) &&
                        !EpisodeMediaValidator.isTooSmall(it.sizeBytes)
                }
                .filter {
                    if (ref.messageId > 0L) {
                        it.messageId == ref.messageId
                    } else {
                        it.fileId == ref.fileId
                    }
                }
                .map { it.toVideo() }
        }

        return emptyList()
    }

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private fun channels(): List<TelegramChannelIndex> =
        store.channels().filter { it.videoWorks.isNotEmpty() }

    private fun buildEntries(channelId: Long?): List<AnimeEntry> {
        val entries = mutableListOf<AnimeEntry>()

        for (channel in channels()) {
            if (channelId != null && channel.chatId != channelId) continue

            val works = channel.videoWorks
                .filterNot { TelegramFileHeuristics.isGenericTitle(it.displayTitle) }
            works.filterNot { it.isUnresolved }.forEach { work ->
                entries += AnimeEntry(work.toSAnime(channel), work.lastFileDate)
            }

            val unresolved = works.filter { it.isUnresolved }
            if (unresolved.isNotEmpty()) {
                entries += AnimeEntry(
                    anime = unknownAnime(channel),
                    lastDate = unresolved.maxOfOrNull { it.lastFileDate } ?: 0L,
                )
            }
        }

        savedStore.snapshot()
            .filter { it.kind == TelegramSavedKind.VIDEO }
            .forEach { work -> entries += AnimeEntry(work.toSAnime(), work.lastDate) }

        return entries
    }

    private fun paginate(entries: List<AnimeEntry>, page: Int): AnimesPage {
        val safePage = page.coerceAtLeast(1)
        val from = (safePage - 1) * PAGE_SIZE
        val slice = entries.drop(from).take(PAGE_SIZE)
        return AnimesPage(slice.map { it.anime }, from + slice.size < entries.size)
    }

    /** Canaux qui contribuent à une œuvre agrégée (même signature titre/saison). */
    fun workChannels(url: String): List<TelegramWorkChannel> {
        val workKey = TelegramUrls.parseAggregatedWork(url) ?: return emptyList()
        return worksFor(workKey).map { (channel, work) ->
            TelegramWorkChannel(
                chatId = channel.chatId,
                title = channel.title,
                workTitle = work.displayTitle,
                fileCount = work.fileCount,
                episodeCount = work.episodeCount,
            )
        }
    }

    private fun worksFor(workKey: String): List<Pair<TelegramChannelIndex, TelegramWorkEntry>> {
        val snapshot = overrides.snapshot()
        val keys = snapshot.sourcesFor(workKey)
        return store.channels().flatMap { channel ->
            channel.videoWorks
                .filter { it.aggregationKey() in keys }
                .map { work ->
                    val meta = snapshot.metadataFor(work.aggregationKey())
                    channel to if (meta == null) {
                        work
                    } else {
                        work.copy(
                            title = meta.title ?: work.title,
                            coverUrl = meta.coverUrl ?: work.coverUrl,
                            description = meta.description ?: work.description,
                            anilistId = meta.anilistId ?: work.anilistId,
                            totalEpisodes = meta.totalEpisodes ?: work.totalEpisodes,
                            year = meta.year ?: work.year,
                            status = meta.status ?: work.status,
                            genres = meta.genres.ifEmpty { work.genres },
                        )
                    }
                }
        }
    }

    private fun aggregatedFiles(workKey: String): List<Pair<TelegramIndexedFile, String>> {
        val contributions = worksFor(workKey)
        if (contributions.isEmpty()) return emptyList()
        val workIds = contributions.map { it.second.id }.toSet()
        return contributions.flatMap { (channel, _) ->
            channel.videoFiles
                .filter { it.workId in workIds }
                .filterNot {
                    TelegramFileHeuristics.isUnsupportedFile(it.fileName, it.mimeType) ||
                        TelegramFileHeuristics.isSuspect(it.fileName, it.caption, it.title) ||
                        !TelegramFileHeuristics.isVideoFile(it.fileName, it.mimeType) ||
                        EpisodeMediaValidator.isTooSmall(it.sizeBytes)
                }
                .map { it to channel.title }
        }
            // Fusion intelligente : un fichier strictement identique présent
            // dans deux variantes n'est ajouté qu'une fois. Les fichiers
            // différents (qualité, langue, épisode) restent tous présents.
            .distinctBy { it.first.identityKey() }
    }

    private fun aggregatedEpisodes(workKey: String): List<SEpisode> {
        val files = aggregatedFiles(workKey).map { it.first }
        if (files.isEmpty()) return emptyList()

        val episodes = mutableListOf<SEpisode>()
        val numbered = files.filter { it.episode != null }
        val seasons = numbered.map { it.season ?: 1 }.distinct().sorted()
        val offsets = mutableMapOf<Int, Int>()
        var offset = 0
        seasons.forEach { season ->
            offsets[season] = offset
            offset += numbered
                .filter { (it.season ?: 1) == season }
                .map { it.episode!! }
                .distinct()
                .size
        }

        numbered.groupBy { (it.season ?: 1) to it.episode!! }
            .forEach { (key, group) ->
                val (season, number) = key
                val absolute = (offsets[season] ?: 0) + number
                val end = group.maxOfOrNull { it.episodeEnd ?: number } ?: number
                val label = if (end >
                    number
                ) {
                    "Épisodes $absolute-${(offsets[season] ?: 0) + end}"
                } else {
                    "Épisode $absolute"
                }
                val suffix = if (seasons.size > 1 && season > 1) " · S$season" else ""
                episodes += SEpisode.create().apply {
                    url = TelegramUrls.aggregatedEpisode(workKey, season, number)
                    name = label + suffix
                    episode_number = absolute.toFloat()
                    date_upload = (group.maxOfOrNull { it.date } ?: 0L) * 1000
                    summary = group.map { it.displayLabel }.distinct().joinToString(", ").takeIf { it.isNotBlank() }
                }
            }

        files.filter { it.episode == null }
            .sortedBy { it.date }
            .forEach { file ->
                episodes += SEpisode.create().apply {
                    url =
                        TelegramUrls.file(
                            file.fileId,
                            file.sizeBytes,
                            file.chatId,
                            extensionOf(file.fileName),
                            messageId = file.messageId,
                        )
                    name = file.title.ifBlank { file.fileName }
                    episode_number = -1f
                    date_upload = file.date * 1000
                    summary = file.displayLabel
                }
            }

        return episodes.sortedWith(compareBy<SEpisode> { it.episode_number < 0 }.thenBy { it.episode_number })
    }

    private fun filesFor(
        channel: TelegramChannelIndex,
        chatId: Long,
        slug: String,
        season: Int?,
    ): List<TelegramIndexedFile> {
        val files = if (slug == TelegramLibraryImporter.UNKNOWN_SLUG) {
            val unresolvedIds = channel.videoWorks
                .filter { it.isUnresolved }
                .map { it.id }
                .toSet()
            channel.videoFiles.filter { it.workId in unresolvedIds }
        } else {
            val id = workId(chatId, slug, season)
            channel.videoFiles.filter { it.workId == id }
        }
        return files.filterNot {
            TelegramFileHeuristics.isUnsupportedFile(it.fileName, it.mimeType) ||
                TelegramFileHeuristics.isSuspect(it.fileName, it.caption, it.title) ||
                !TelegramFileHeuristics.isVideoFile(it.fileName, it.mimeType) ||
                EpisodeMediaValidator.isTooSmall(it.sizeBytes)
        }
    }

    private fun workId(chatId: Long, slug: String, season: Int?): String =
        "$chatId:$slug:${season ?: 0}"

    // ------------------------------------------------------------------
    // Œuvres validées manuellement
    // ------------------------------------------------------------------

    private fun savedEpisodes(work: TelegramSavedWork): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val usable = work.files.filterNot {
            TelegramFileHeuristics.isSuspect(it.fileName, it.caption, work.title) ||
                !TelegramFileHeuristics.isVideoFile(it.fileName, it.mimeType) ||
                EpisodeMediaValidator.isTooSmall(it.sizeBytes)
        }

        val numbered = usable.filter { it.episode != null }
        val seasons = numbered.map { it.season ?: 1 }.distinct().sorted()
        val offsets = mutableMapOf<Int, Int>()
        var offset = 0
        seasons.forEach { season ->
            offsets[season] = offset
            offset += numbered
                .filter { (it.season ?: 1) == season }
                .map { it.episode!! }
                .distinct()
                .size
        }

        numbered
            .groupBy { it.season to it.episode!! }
            .forEach { entry ->
                val season = entry.key.first
                val seasonKey = season ?: 1
                val number = entry.key.second
                val group = entry.value
                val seasonOffset = offsets[seasonKey] ?: 0
                val absolute = seasonOffset + number
                val end = group.maxOfOrNull { it.episodeEnd ?: number } ?: number
                val label = if (end > number) {
                    "Épisodes $absolute-${seasonOffset + end}"
                } else {
                    "Épisode $absolute"
                }
                val suffix = if (seasons.size > 1 && seasonKey > 1) " · S$seasonKey" else ""

                episodes += SEpisode.create().apply {
                    url = TelegramUrls.savedUnit(work.id, season, number)
                    name = label + suffix
                    episode_number = absolute.toFloat()
                    date_upload = (group.maxOfOrNull { it.date } ?: 0L) * 1000
                    summary = group.map { it.displayLabel() }
                        .distinct()
                        .joinToString(", ")
                        .takeIf { it.isNotBlank() }
                }
            }

        usable.filter { it.episode == null }
            .sortedBy { it.date }
            .forEach { file ->
                episodes += SEpisode.create().apply {
                    url = TelegramUrls.savedFile(work.id, file.messageId)
                    name = file.displayName()
                    episode_number = -1f
                    date_upload = file.date * 1000
                    summary = file.displayLabel()
                }
            }

        return episodes.sortedWith(
            compareBy<SEpisode> { it.episode_number < 0 }.thenBy { it.episode_number },
        )
    }

    private fun TelegramSavedWork.toSAnime(): SAnime = SAnime.create().apply {
        url = TelegramUrls.savedAnime(id)
        title = this@toSAnime.title
        description = "${files.size} fichier(s) validé(s) manuellement."
        initialized = false
    }

    private fun TelegramSavedFile.displayName(): String =
        fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

    private fun TelegramSavedFile.displayLabel(): String = buildList {
        language?.takeIf { it.isNotBlank() }?.let(::add)
        variant?.takeIf { it.isNotBlank() }?.let(::add)
        fileName.substringAfterLast('.', "")
            .uppercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
            ?.let(::add)
    }.joinToString(" · ")

    private fun TelegramSavedFile.toVideo(): Video {
        val size = formatSize(sizeBytes)
        val title = buildString {
            if (!language.isNullOrBlank()) append(language)
            if (!variant.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(variant)
            }
            if (isNotEmpty()) append(" · ")
            append(size)
        }

        return Video(
            videoUrl = TelegramUrls.file(
                fileId,
                sizeBytes,
                chatId,
                extensionOf(fileName),
                messageId = messageId,
            ),
            videoTitle = title,
            resolution = resolutionOf(variant),
        )
    }

    private fun TelegramWorkEntry.toSAnime(channel: TelegramChannelIndex): SAnime {
        val slug = id.split(':').getOrNull(1).orEmpty()
        return SAnime.create().apply {
            url = TelegramUrls.work(channel.chatId, slug, season)
            title = displayTitle
            thumbnail_url = coverUrl
            background_url = null
            description = this@toSAnime.description
            genre = genres.joinToString(", ").takeIf { it.isNotBlank() }
            status = this@toSAnime.status.toSAnimeStatus()
            initialized = false
        }
    }

    private fun unknownAnime(channel: TelegramChannelIndex): SAnime {
        val cleaned = TelegramTitleParser.cleanChannelName(channel.title)
            .ifBlank { channel.title }
        return SAnime.create().apply {
            url = TelegramUrls.work(channel.chatId, TelegramLibraryImporter.UNKNOWN_SLUG, null)
            title = "Non reconnus — $cleaned"
            description = "Fichiers du canal « $cleaned » qui n'ont pas pu être associés à une œuvre."
            initialized = false
        }
    }

    private fun TelegramIndexedFile.toVideo(channelTitle: String? = null): Video {
        val size = formatSize(sizeBytes)
        val title = buildString {
            if (!channelTitle.isNullOrBlank()) append(channelTitle)
            if (!language.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(language)
            }
            if (!variant.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(variant)
            }
            if (isNotEmpty()) append(" · ")
            append(size)
        }

        return Video(
            videoUrl = TelegramUrls.file(
                fileId,
                sizeBytes,
                chatId,
                extensionOf(fileName),
                messageId = messageId,
            ),
            videoTitle = title,
            resolution = resolutionOf(variant),
        )
    }

    private fun extensionOf(name: String): String? =
        name.substringAfterLast('.', "").takeIf { it.length in 1..5 }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "taille inconnue"
        val mb = bytes / 1_048_576.0
        return if (mb >= 1024) {
            String.format(Locale.FRENCH, "%.2f Go", mb / 1024)
        } else {
            String.format(Locale.FRENCH, "%.1f Mo", mb)
        }
    }

    private fun resolutionOf(variant: String?): Int? {
        val value = variant.orEmpty()
        return when {
            value.contains("4K", true) -> 2160
            value.contains("1080", true) -> 1080
            value.contains("720", true) -> 720
            value.contains("480", true) -> 480
            else -> null
        }
    }

    private fun String?.toSAnimeStatus(): Int = when (this?.uppercase(Locale.ROOT)) {
        "FINISHED" -> SAnime.COMPLETED
        "RELEASING" -> SAnime.ONGOING
        "CANCELLED" -> SAnime.CANCELLED
        "HIATUS" -> SAnime.ON_HIATUS
        "NOT_YET_RELEASED" -> SAnime.UPCOMING
        else -> SAnime.UNKNOWN
    }

    // ------------------------------------------------------------------
    // Endpoints HTTP factices (jamais appelés)
    // ------------------------------------------------------------------

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular/$page")

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search")

    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create()

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    override fun videoListParse(response: Response): List<Video> = emptyList()

    private data class AnimeEntry(val anime: SAnime, val lastDate: Long)

    companion object {
        const val ID = 987654321L
        private const val PAGE_SIZE = 50
    }
}

data class TelegramWorkChannel(
    val chatId: Long,
    val title: String,
    val workTitle: String,
    val fileCount: Int,
    val episodeCount: Int,
)

/**
 * Filtre « Canal » de la source vidéo.
 */
class TelegramChannelFilter(
    channels: List<TelegramChannelIndex>,
) : AnimeFilter.Select<String>(
    name = "Canal",
    values = arrayOf("Tous") + channels.map { it.title }.toTypedArray(),
) {
    fun selectedChatId(channels: List<TelegramChannelIndex>): Long? {
        if (state <= 0) return null
        return channels.getOrNull(state - 1)?.chatId
    }
}
