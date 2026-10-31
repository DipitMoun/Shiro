package eu.kanade.tachiyomi.data.telegram.saved

import eu.kanade.tachiyomi.data.telegram.TelegramFileHeuristics
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexManager
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexedFile
import eu.kanade.tachiyomi.data.telegram.index.TelegramLibraryImporter
import eu.kanade.tachiyomi.data.telegram.index.TelegramMediaKind
import eu.kanade.tachiyomi.data.telegram.index.TelegramResolvedWork
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkResolver
import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Résultat d'une passe de veille, utilisé pour la notification. */
data class TelegramWatchUpdate(
    val workId: String,
    val title: String,
    val added: Int,
)

/**
 * Opérations sur les œuvres validées : renommage, resynchronisation, veille,
 * suppression et résolution AniList. Les appels réseau/synchro restent hors du
 * fil UI ; l'AniList n'est jamais bloquant.
 */
class TelegramSavedWorkManager(
    private val store: TelegramSavedWorkStore,
    private val indexManager: TelegramIndexManager,
    private val importer: TelegramLibraryImporter,
    private val resolver: TelegramWorkResolver,
    private val externalResolver: TelegramExternalMetadataResolver,
) {
    private val mutex = Mutex()

    suspend fun rename(work: TelegramSavedWork, title: String) {
        val clean = title.trim().ifBlank { work.title }
        val updated = work.copy(title = clean)
        store.save(updated)
        importer.updateSavedWorkEntry(updated)
    }

    suspend fun setWatch(work: TelegramSavedWork, enabled: Boolean, intervalMinutes: Int) {
        store.save(
            work.copy(
                watchEnabled = enabled,
                watchIntervalMinutes = intervalMinutes.coerceIn(MIN_WATCH_MINUTES, MAX_WATCH_MINUTES),
            ),
        )
    }

    suspend fun delete(work: TelegramSavedWork) {
        // On retire d'abord l'entrée de bibliothèque : si cela échoue, l'œuvre
        // reste dans « Mes œuvres » au lieu de laisser une entrée orpheline.
        importer.deleteSavedWorkEntry(work)
        store.remove(work.id)
    }

    /**
     * Re-analyse et répare localement les œuvres déjà stockées.
     * Les œuvres Telegram ne sont pas reconstruites automatiquement après une
     * amélioration du parseur : sans cette passe, une œuvre comme Steel Ball Run
     * garde un ancien titre/numéro de saison et ne retrouve pas ses épisodes.
     * On réutilise aussi l'index déjà présent sur le téléphone (aucun scan réseau).
     */
    suspend fun reanalyzeAll(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            var changed = 0
            store.snapshot().forEach { work ->
                val repaired = refreshLocked(work, syncChannels = false)
                if (repaired != work) changed++
            }
            changed
        }
    }

    /** Resynchronise une œuvre : récupère les nouveaux fichiers des canaux sources. */
    suspend fun refresh(work: TelegramSavedWork): TelegramSavedWork = withContext(Dispatchers.IO) {
        mutex.withLock {
            refreshLocked(work, syncChannels = true)
        }
    }

    /**
     * Résout (ou corrige) les métadonnées. Retourne `null` si aucun résultat
     * fiable n'a été trouvé. AniList reste le fournisseur par défaut ; MAL
     * (Jikan), Kitsu et Shikimori sont disponibles en secours.
     */
    suspend fun resolve(
        work: TelegramSavedWork,
        forcedTitle: String? = null,
        provider: TelegramMetadataProvider = TelegramMetadataProvider.ANILIST,
    ): TelegramResolvedWork? {
        val kind = if (work.kind == TelegramSavedKind.VIDEO) {
            TelegramMediaKind.VIDEO
        } else {
            TelegramMediaKind.DOCUMENT
        }

        // Pour une œuvre fusionnée S1+S2, on résout la saison la plus ancienne
        // (l'entrée principale), pas la dernière saison postée.
        val season = if (kind == TelegramMediaKind.VIDEO) {
            work.files.map { it.season ?: 1 }.minOrNull() ?: 1
        } else {
            null
        }

        val resolved = if (provider == TelegramMetadataProvider.ANILIST) {
            resolver.resolve(
                title = work.sourceTitle ?: work.title,
                season = season,
                kind = kind,
                forcedTitle = forcedTitle,
            )?.copy(provider = TelegramMetadataProvider.ANILIST.name)
        } else {
            externalResolver.search(
                provider = provider,
                title = forcedTitle?.takeIf { it.isNotBlank() } ?: work.sourceTitle ?: work.title,
                kind = kind,
            )
        } ?: return null

        if (resolved.anilistId == null && resolved.providerId == null) return null

        val updated = work.copy(resolution = resolved)
        store.save(updated)
        importer.updateSavedWorkEntry(updated)
        return resolved
    }

    /**
     * Passe de veille : traite les œuvres dont l'intervalle est écoulé.
     *
     * @param syncChannels vrai si la synchro globale n'a pas déjà rafraîchi les
     *        index (mode veille seule).
     */
    suspend fun runDue(syncChannels: Boolean = false): List<TelegramWatchUpdate> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val due = store.snapshot().filter { work ->
                    work.watchEnabled && isDue(work, now)
                }

                due.mapNotNull { work ->
                    val before = work.files.size
                    val refreshed = runCatching {
                        refreshLocked(work, syncChannels)
                    }.getOrElse {
                        logcat(LogPriority.WARN, it) { "Veille Telegram impossible : ${work.title}" }
                        work
                    }

                    store.save(refreshed.copy(lastWatchAt = now))
                    val added = refreshed.files.size - before
                    if (added > 0) {
                        TelegramWatchUpdate(workId = work.id, title = refreshed.title, added = added)
                    } else {
                        null
                    }
                }
            }
        }

    private suspend fun refreshLocked(
        work: TelegramSavedWork,
        syncChannels: Boolean,
    ): TelegramSavedWork {
        val analyzed = reanalyze(work)
        val chatIds = analyzed.files.map { it.chatId }.distinct()
        if (chatIds.isEmpty()) return analyzed

        if (syncChannels) {
            chatIds.forEach { chatId ->
                runCatching { indexManager.syncChannel(chatId) }
                    .onFailure {
                        logcat(LogPriority.WARN, it) { "Resynchronisation Telegram impossible ($chatId)" }
                    }
            }
        }

        val kind = if (analyzed.kind == TelegramSavedKind.VIDEO) {
            TelegramMediaKind.VIDEO
        } else {
            TelegramMediaKind.DOCUMENT
        }
        val known = analyzed.files.map { it.chatId to it.messageId }.toSet()
        val lastDate = analyzed.lastDate

        val channelFiles = chatIds.flatMap { indexManager.channel(it)?.files.orEmpty() }
        // Le messageId est stable, contrairement au titre. On retrouve le workId
        // courant de l'index à partir des fichiers déjà connus : un changement de
        // parseur (« Part 7 » -> « Steel Ball Run ») ne casse plus la resynchro.
        val knownWorkIdsByChat = channelFiles
            .filter { (it.chatId to it.messageId) in known }
            .groupBy { it.chatId }
            .mapValues { (_, files) -> files.map { it.workId }.toSet() }
        val titleSignatures = buildSet {
            fun addTitle(title: String) {
                add(TelegramTitleParser.signature(title))
                add(TelegramTitleParser.signature(TelegramTitleParser.canonicalTitle(title)))
            }
            addTitle(analyzed.title)
            analyzed.sourceTitle?.let(::addTitle)
        }

        val newFiles = channelFiles
            .filter { it.mediaKind == kind }
            .filter { (it.chatId to it.messageId) !in known }
            .filter { indexed ->
                val sameKnownWork =
                    knownWorkIdsByChat[indexed.chatId]?.contains(indexed.workId) == true
                val titleMatches =
                    TelegramTitleParser.signature(indexed.title) in titleSignatures ||
                        TelegramTitleParser.signature(
                            TelegramTitleParser.canonicalTitle(indexed.title),
                        ) in titleSignatures
                when {
                    // Le workId de l'index est une preuve plus forte que le nom
                    // du fichier : les exports VID/YouCut avec titre en légende
                    // restent donc valides.
                    sameKnownWork -> true
                    titleMatches -> !TelegramFileHeuristics.isSuspect(
                        indexed.fileName,
                        indexed.caption,
                        analyzed.title,
                    )
                    else -> false
                }
            }
            // Ne réintroduit pas les anciens fichiers volontairement exclus :
            // seuls les fichiers postés après le dernier connu sont ajoutés.
            .filter { lastDate == 0L || it.date > lastDate }
            .distinctBy { it.chatId to it.messageId }
            .map { it.toSavedFile() }

        if (newFiles.isEmpty()) {
            if (analyzed != work) {
                store.save(analyzed)
                importer.updateSavedWorkEntry(analyzed, resetInitialized = true)
            }
            return analyzed
        }

        val updated = analyzed.copy(
            files = (analyzed.files + newFiles)
                .distinctBy { it.chatId to it.messageId }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE }, { it.date })),
        )
        store.save(updated)
        importer.updateSavedWorkEntry(updated, resetInitialized = true)
        return updated
    }

    private fun reanalyze(work: TelegramSavedWork): TelegramSavedWork {
        val kind = if (work.kind == TelegramSavedKind.VIDEO) {
            TelegramTitleParser.Kind.VIDEO
        } else {
            TelegramTitleParser.Kind.DOCUMENT
        }
        val files = work.files.map { file ->
            val parsed = TelegramTitleParser.parse(
                fileName = file.fileName,
                caption = file.caption,
                kind = kind,
            )
            file.copy(
                season = parsed.season ?: file.season,
                episode = parsed.episode ?: file.episode,
                episodeEnd = parsed.episodeEnd ?: file.episodeEnd,
                chapter = parsed.chapterStart ?: file.chapter,
                chapterEnd = parsed.chapterEnd ?: file.chapterEnd,
                language = parsed.language ?: file.language,
                variant = parsed.variant ?: file.variant,
            )
        }
        return if (files == work.files) work else work.copy(files = files)
    }

    private fun isDue(work: TelegramSavedWork, now: Long): Boolean {
        if (work.lastWatchAt <= 0L) return true
        val intervalMs = work.watchIntervalMinutes.coerceAtLeast(MIN_WATCH_MINUTES) * 60_000L
        return now - work.lastWatchAt >= intervalMs
    }

    private fun TelegramIndexedFile.toSavedFile(): TelegramSavedFile = TelegramSavedFile(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        caption = caption,
        date = date,
        threadId = threadId,
        season = season,
        episode = episode,
        episodeEnd = episodeEnd,
        chapter = chapterStart,
        chapterEnd = chapterEnd,
        language = language,
        variant = variant,
    )

    companion object {
        const val MIN_WATCH_MINUTES = 15
        const val MAX_WATCH_MINUTES = 24 * 60
    }
}
