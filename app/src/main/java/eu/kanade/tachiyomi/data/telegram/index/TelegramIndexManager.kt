package eu.kanade.tachiyomi.data.telegram.index

import eu.kanade.tachiyomi.data.media.EpisodeMediaValidator
import eu.kanade.tachiyomi.data.telegram.TelegramFileHeuristics
import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramChannelScraper
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramFileKind
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramFileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Construit et maintient l'index des canaux suivis.
 *
 * Un seul verrou : deux synchronisations simultanées (onglet Telegram et
 * WorkManager) ne peuvent pas corrompre un index.
 */
class TelegramIndexManager(
    private val scraper: TelegramChannelScraper,
    private val store: TelegramIndexStore,
    private val preferences: TelegramPreferences,
    private val resolver: TelegramWorkResolver,
) {
    private val mutex = Mutex()

    private val _syncState = MutableStateFlow<TelegramSyncState>(TelegramSyncState.Idle)
    val syncState: StateFlow<TelegramSyncState> = _syncState.asStateFlow()

    fun channels(): List<TelegramChannelIndex> = store.channels()

    fun channelSummaries(): List<TelegramChannelSummary> = store.channelSummaries()

    fun channel(chatId: Long): TelegramChannelIndex? = store.channel(chatId)

    fun works(kind: TelegramMediaKind): List<TelegramWorkEntry> =
        store.channels().flatMap { channel ->
            when (kind) {
                TelegramMediaKind.VIDEO -> channel.videoWorks
                TelegramMediaKind.DOCUMENT -> channel.documentWorks
            }
        }

    // ------------------------------------------------------------------
    // Synchronisation
    // ------------------------------------------------------------------

    suspend fun syncChannel(
        chatId: Long,
        full: Boolean = false,
        allowBackfill: Boolean = true,
    ): TelegramSyncSummary = withContext(Dispatchers.IO) {
        mutex.withLock { syncLocked(chatId, full, allowBackfill) }
    }

    private suspend fun syncLocked(
        chatId: Long,
        full: Boolean,
        allowBackfill: Boolean,
    ): TelegramSyncSummary {
        // Un index produit par un ancien parseur est d'abord ré-analysé localement.
        // On note la version AVANT la ré-analyse : un changement de classement
        // (ex. vidéos envoyées en document) impose un nouveau scan complet.
        val beforeRebuild = store.channel(chatId)
        val needsFullRescan = beforeRebuild?.needsRescan == true
        rebuildLocked(chatId)

        val existing = store.channel(chatId)
        val effectiveFull = full || needsFullRescan
        val title = existing?.title
            ?: preferences.channelTitle(chatId)
            ?: "Canal Telegram"

        _syncState.value = TelegramSyncState.Syncing(
            chatId = chatId,
            title = title,
            current = 0,
            total = preferences.allChatIds.size,
        )

        return try {
            val info = scraper.getChannelInfo(chatId)

            // Passe 1 : messages récents, au-dessus du dernier message examiné.
            val since = if (effectiveFull) {
                0L
            } else {
                maxOf(existing?.lastMessageId ?: 0L, existing?.newestScannedMessageId ?: 0L)
            }
            val top = scraper.scan(
                channelId = chatId,
                messageLimit = if (effectiveFull) BACKFILL_MESSAGE_LIMIT else INCREMENTAL_MESSAGE_LIMIT,
                sinceMessageId = since,
            )

            // Passe 2 : rétro-remplissage reprenant au curseur précédent. Sans
            // elle, un index créé avec une limite trop basse ne verrait jamais
            // les fichiers postés avant sa création.
            val backfill = if (!allowBackfill || effectiveFull || existing?.historyComplete == true) {
                null
            } else {
                val from = existing?.oldestScannedMessageId
                    ?.takeIf { it > 0L }
                    ?: top.oldestMessageId
                scraper.scan(
                    channelId = chatId,
                    messageLimit = BACKFILL_MESSAGE_LIMIT,
                    fromMessageId = from,
                )
            }

            val metadata = top.files + backfill?.files.orEmpty()

            val kindHint = kindHint(chatId)
            val parsed = metadata
                .mapNotNull { toIndexedFile(it, kindHint, info.username) }

            // On conserve toujours les fichiers déjà indexés : une passe ne
            // couvre qu'une tranche d'historique et ne doit rien effacer.
            val merged = if (existing == null) {
                parsed.distinctBy { it.fileId }
            } else {
                merge(existing.files, parsed)
            }
            // Les teasers indexés avant l'ajout du seuil de taille sont retirés.
            val retained = merged.filterNot {
                TelegramFileHeuristics.isUnsupportedFile(it.fileName, it.mimeType) ||
                    (
                        it.mediaKind == TelegramMediaKind.VIDEO &&
                            EpisodeMediaValidator.isTooSmall(it.sizeBytes)
                        )
            }

            val previousWorks = existing?.works.orEmpty().associateBy { it.id }
            val (works, indexedFiles) = buildIndex(
                chatId = chatId,
                channelTitle = info.title,
                files = retained,
                previousWorks = previousWorks,
            )

            val lastMessageId = maxOf(
                existing?.lastMessageId ?: 0L,
                metadata.maxOfOrNull { it.messageId } ?: 0L,
            )

            val messagesScanned = (if (effectiveFull) 0 else existing?.messagesScanned ?: 0) +
                top.messagesScanned +
                (backfill?.messagesScanned ?: 0)

            val oldestScannedMessageId = sequenceOf(
                if (effectiveFull) 0L else existing?.oldestScannedMessageId ?: 0L,
                top.oldestMessageId,
                backfill?.oldestMessageId ?: 0L,
            ).filter { it > 0L }.minOrNull() ?: 0L

            val newestScannedMessageId = maxOf(
                if (effectiveFull) 0L else existing?.newestScannedMessageId ?: 0L,
                top.newestMessageId,
                backfill?.newestMessageId ?: 0L,
            )

            val historyComplete = if (effectiveFull) {
                top.reachedStart
            } else {
                existing?.historyComplete == true ||
                    (top.reachedStart && since == 0L) ||
                    backfill?.reachedStart == true
            }

            val index = TelegramChannelIndex(
                chatId = chatId,
                title = info.title,
                username = info.username,
                isChannel = info.isChannel,
                lastMessageId = lastMessageId,
                lastSyncAt = System.currentTimeMillis(),
                indexVersion = INDEX_VERSION,
                newestScannedMessageId = newestScannedMessageId,
                oldestScannedMessageId = oldestScannedMessageId,
                messagesScanned = messagesScanned,
                historyComplete = historyComplete,
                isMember = info.isMember,
                isForum = info.isForum,
                needsRescan = false,
                files = indexedFiles.sortedByDescending { it.date },
                works = works.sortedBy { it.title.lowercase() },
            )

            store.writeChannel(index)
            preferences.rememberChannel(chatId, info.title)

            val previousKeys = existing?.files.orEmpty()
                .map { if (it.messageId > 0L) it.messageId else -it.fileId.toLong() }
                .toSet()
            val newFiles = retained.filter {
                (if (it.messageId > 0L) it.messageId else -it.fileId.toLong()) !in previousKeys
            }

            _syncState.value = TelegramSyncState.Idle

            TelegramSyncSummary(
                channel = index,
                newFiles = newFiles.size,
                newEpisodes = newFiles.count { it.episode != null || it.chapterStart != null },
                newWorks = works.map { it.displayTitle }
                    .filterNot { it in previousWorks.values.map { work -> work.displayTitle } },
                messagesScanned = messagesScanned,
                historyComplete = historyComplete,
            )
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "Synchronisation Telegram échouée pour $chatId" }
            _syncState.value = TelegramSyncState.Error(t.message ?: "Synchronisation échouée")
            throw t
        }
    }

    /**
     * Scanne tout l'historique d'un canal, par tranches successives, jusqu'à
     * atteindre le début ou épuiser le budget. Reprenable : chaque passe
     * enregistre son curseur, une interruption ne perd rien.
     */
    suspend fun scanFullHistory(chatId: Long, maxPasses: Int = 40): Int {
        var total = 0
        repeat(maxPasses) { pass ->
            val summary = syncChannel(chatId, full = pass == 0)
            total += summary.messagesScanned

            val channel = store.channel(chatId) ?: return total
            if (channel.historyComplete) return total
            if (summary.messagesScanned == 0) return total
        }
        return total
    }

    /** Rejoint le canal puis rafraîchit son index. */
    suspend fun joinChannel(chatId: Long): TelegramSyncSummary {
        scraper.joinChannel(chatId)
        return syncChannel(chatId)
    }

    suspend fun syncAll(full: Boolean = false): List<TelegramSyncSummary> {
        val ids = preferences.allChatIds
        val results = mutableListOf<TelegramSyncSummary>()

        ids.forEachIndexed { index, chatId ->
            val title = preferences.channelTitle(chatId) ?: "Canal Telegram"
            _syncState.value = TelegramSyncState.Syncing(
                chatId = chatId,
                title = title,
                current = index,
                total = ids.size,
            )
            runCatching {
                withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                    syncChannel(chatId, full, allowBackfill = false)
                }
            }
                .onSuccess { summary ->
                    if (summary != null) {
                        results += summary
                    } else {
                        logcat(LogPriority.WARN) { "Synchro Telegram expirée pour $chatId" }
                    }
                }
                .onFailure { logcat(LogPriority.WARN, it) { "Canal $chatId ignoré" } }
        }

        preferences.lastGlobalSync = System.currentTimeMillis()
        _syncState.value = TelegramSyncState.Idle
        return results
    }

    fun removeChannel(chatId: Long) {
        store.deleteChannel(chatId)
        preferences.forgetChannel(chatId)
    }

    /**
     * Re-parse localement les index produits par une version antérieure du
     * parseur. Aucun appel Telegram : les noms et légendes sont déjà stockés.
     */
    suspend fun rebuildIfNeeded(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { rebuildLocked(chatId = null) }
    }

    /**
     * Re-parse tous les index un par un. Contrairement à [rebuildIfNeeded],
     * ne garde pas tous les canaux en mémoire : important sur mobile avec un
     * index principal de plusieurs dizaines de Mo.
     */
    suspend fun rebuildAllIfNeeded(): Int = withContext(Dispatchers.IO) {
        var rebuilt = 0
        store.channelIds().forEach { chatId ->
            rebuilt += mutex.withLock { rebuildLocked(chatId) }
        }
        rebuilt
    }

    /** Re-parse local d'un seul canal, sans réseau. */
    suspend fun rebuildChannel(chatId: Long): Int = withContext(Dispatchers.IO) {
        mutex.withLock { rebuildLocked(chatId) }
    }

    /**
     * Re-parse localement les index produits par une version antérieure du
     * parseur. Aucun appel Telegram : les noms et légendes sont déjà stockés.
     */
    private suspend fun rebuildLocked(chatId: Long?): Int {
        var rebuilt = 0

        // Une synchro mono-canal ne doit jamais décoder les index des autres
        // canaux : c'était le principal coût caché de l'ouverture d'une collection.
        val channels = if (chatId != null) {
            listOfNotNull(store.channel(chatId))
        } else {
            store.channels()
        }
        channels.forEach { channel ->
            if (chatId != null && channel.chatId != chatId) return@forEach
            // Seuls les index d'avant la récupération des documents sont
            // reconstruits. Les mises à jour de parseur/classification sont
            // appliquées à la volée (recherche/sources), sans reparcourir les
            // dizaines de milliers de fichiers à chaque montée de version.
            if (channel.indexVersion >= LEGACY_REBUILD_VERSION) return@forEach

            val files = channel.files.mapNotNull { file ->
                val kind = if (file.mediaKind == TelegramMediaKind.VIDEO) {
                    TelegramTitleParser.Kind.VIDEO
                } else {
                    TelegramTitleParser.Kind.DOCUMENT
                }
                val parsed = TelegramTitleParser.parse(
                    fileName = file.fileName,
                    caption = file.caption,
                    kind = kind,
                    channelUsername = channel.username,
                )
                val title = parsed.title.ifBlank { file.fileName.substringBeforeLast('.') }
                if (TelegramFileHeuristics.isUnsupportedFile(file.fileName, file.mimeType)) {
                    return@mapNotNull null
                }
                val isDocument = TelegramFileHeuristics.isComicDocument(file.fileName, file.mimeType)
                val isVideo = !isDocument &&
                    TelegramFileHeuristics.isVideoFile(file.fileName, file.mimeType)
                val mediaKind = when {
                    isDocument -> TelegramMediaKind.DOCUMENT
                    isVideo -> TelegramMediaKind.VIDEO
                    else -> file.mediaKind
                }

                file.copy(
                    mediaKind = mediaKind,
                    title = title,
                    season = parsed.season,
                    episode = parsed.episode,
                    episodeEnd = parsed.episodeEnd,
                    chapterStart = parsed.chapterStart,
                    chapterEnd = parsed.chapterEnd,
                    language = parsed.language,
                    variant = parsed.variant,
                )
            }.filterNot {
                it.mediaKind == TelegramMediaKind.VIDEO &&
                    EpisodeMediaValidator.isTooSmall(it.sizeBytes)
            }

            val (works, indexed) = buildIndex(
                chatId = channel.chatId,
                channelTitle = channel.title,
                files = files,
                previousWorks = channel.works.associateBy { it.id },
                // Réparation locale : jamais d'appel AniList en masse.
                resolveRemote = false,
            )

            store.writeChannel(
                channel.copy(
                    indexVersion = INDEX_VERSION,
                    // Une simple mise à niveau de parseur/classification ne doit
                    // pas relancer un scan réseau complet de tous les canaux.
                    // Seuls les index d'avant la récupération des documents (v8)
                    // nécessitent une resynchronisation.
                    needsRescan = channel.indexVersion < 8,
                    files = indexed.sortedByDescending { it.date },
                    works = works.sortedBy { it.title.lowercase() },
                ),
                keepInMemory = false,
            )
            rebuilt++
        }

        return rebuilt
    }

    // ------------------------------------------------------------------
    // Construction de l'index
    // ------------------------------------------------------------------

    private val trailingEpisode = Regex(
        """(?i)^\s*(.+?)[\s._-]+0*(\d{1,3})(?:\s*(?:VF|VOSTFR|VOST|VO|MULTI|HD|FHD|4K))?\s*$""",
    )

    private fun kindHint(chatId: Long): TelegramMediaKind? {
        val video = chatId in preferences.videoChats
        val doc = chatId in preferences.docChats
        return when {
            video && !doc -> TelegramMediaKind.VIDEO
            doc && !video -> TelegramMediaKind.DOCUMENT
            else -> null
        }
    }

    private fun toIndexedFile(
        metadata: TelegramFileMetadata,
        hint: TelegramMediaKind?,
        channelUsername: String?,
    ): TelegramIndexedFile? {
        val mime = metadata.mimeType.orEmpty().lowercase()
        val extension = metadata.fileName.substringAfterLast('.', "").lowercase()

        if (TelegramFileHeuristics.isUnsupportedFile(metadata.fileName, metadata.mimeType)) {
            return null
        }

        // Un fichier vidéo reste une vidéo, même si Telegram l'a envoyé comme
        // document. Les images/archives restent des documents, même dans un
        // canal configuré en mode Vidéo (sinon une capture devient un épisode).
        val isComic = TelegramFileHeuristics.isComicDocument(metadata.fileName, metadata.mimeType)
        // Un canal configuré en « Vidéo » ne doit pas transformer un .cbz/.pdf
        // en épisode : l'extension et le MIME priment sur le mode du canal.
        val isVideoLike = !isComic &&
            (
                metadata.kind == TelegramFileKind.VIDEO ||
                    metadata.kind == TelegramFileKind.ANIMATION ||
                    metadata.kind == TelegramFileKind.VIDEO_NOTE ||
                    mime.startsWith("video/") ||
                    extension in VIDEO_EXTENSIONS
                )

        val mediaKind = when {
            isComic -> TelegramMediaKind.DOCUMENT
            isVideoLike -> TelegramMediaKind.VIDEO
            hint == TelegramMediaKind.VIDEO -> TelegramMediaKind.VIDEO
            hint == TelegramMediaKind.DOCUMENT -> TelegramMediaKind.DOCUMENT
            else -> TelegramMediaKind.DOCUMENT
        }

        if (mediaKind == TelegramMediaKind.DOCUMENT && !isComic) return null

        if (metadata.kind == TelegramFileKind.AUDIO ||
            metadata.kind == TelegramFileKind.VOICE_NOTE ||
            metadata.kind == TelegramFileKind.PHOTO
        ) {
            return null
        }

        val parserKind = if (mediaKind == TelegramMediaKind.VIDEO) {
            TelegramTitleParser.Kind.VIDEO
        } else {
            TelegramTitleParser.Kind.DOCUMENT
        }

        val parsed = TelegramTitleParser.parse(
            fileName = metadata.fileName,
            caption = metadata.caption,
            kind = parserKind,
            channelUsername = channelUsername,
        )

        val workTitle = parsed.title.ifBlank { metadata.fileName.substringBeforeLast('.') }
        val workId = workId(metadata.chatId, workTitle, parsed.season)

        // Les vidéos trop légères sont des teasers/trailers : on les écarte pour
        // ne pas polluer les épisodes. Les documents (mangas, PDF, archives)
        // n'ont aucune limite de taille.
        if (mediaKind == TelegramMediaKind.VIDEO &&
            EpisodeMediaValidator.isTooSmall(metadata.sizeBytes)
        ) {
            return null
        }

        return TelegramIndexedFile(
            fileId = metadata.fileId,
            messageId = metadata.messageId,
            chatId = metadata.chatId,
            fileName = metadata.fileName,
            caption = metadata.caption,
            sizeBytes = metadata.sizeBytes,
            mimeType = metadata.mimeType,
            durationSeconds = metadata.durationSeconds,
            date = metadata.date,
            threadId = metadata.messageThreadId,
            mediaKind = mediaKind,
            title = workTitle,
            season = parsed.season,
            episode = parsed.episode,
            episodeEnd = parsed.episodeEnd,
            chapterStart = parsed.chapterStart,
            chapterEnd = parsed.chapterEnd,
            language = parsed.language,
            variant = parsed.variant,
            workId = workId,
        )
    }

    private fun merge(
        existing: List<TelegramIndexedFile>,
        incoming: List<TelegramIndexedFile>,
    ): List<TelegramIndexedFile> {
        // Un message Telegram ne porte qu'un fichier : l'identifiant de message
        // est la clé stable. Les fileId TDLib, eux, peuvent changer d'une
        // session à l'autre et dupliquaient les entrées.
        val byKey = existing.associateBy { fileKey(it) }.toMutableMap()
        incoming.forEach { byKey[fileKey(it)] = it }
        return byKey.values.toList()
    }

    private fun fileKey(file: TelegramIndexedFile): Long =
        if (file.messageId > 0L) file.messageId else -file.fileId.toLong()

    /**
     * Certains exports ont un titre « Black Clover 84 » au lieu de
     * « Black Clover » + épisode 84. Sans ce rattrapage, l'épisode devient une
     * œuvre non reconnue séparée et manque dans l'œuvre principale.
     */
    private fun recoverTrailingEpisode(file: TelegramIndexedFile): TelegramIndexedFile {
        if (file.mediaKind != TelegramMediaKind.VIDEO || file.episode != null) return file

        val match = trailingEpisode.find(file.title) ?: return file
        val base = match.groupValues[1].trim().trimEnd('-', '–', ':', '_', '.').trim()
        val number = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..999 } ?: return file
        if (base.length < 2) return file

        return file.copy(
            title = base,
            episode = number,
        )
    }

    private suspend fun buildIndex(
        chatId: Long,
        channelTitle: String,
        files: List<TelegramIndexedFile>,
        previousWorks: Map<String, TelegramWorkEntry>,
        resolveRemote: Boolean = true,
    ): Pair<List<TelegramWorkEntry>, List<TelegramIndexedFile>> {
        val forced = preferences.forcedWork(chatId)
        val cleanedChannel = TelegramTitleParser.cleanChannelName(channelTitle)

        // Nettoyage des doublons historiques : même message = même fichier.
        val uniqueFiles = files
            .groupBy { if (it.messageId > 0L) it.messageId else -it.fileId.toLong() }
            .values
            .map { entries -> recoverTrailingEpisode(entries.first()) }

        val titles = uniqueFiles.associate { file ->
            file.fileId to (forced ?: file.title.ifBlank { cleanedChannel })
        }

        val grouping = TelegramWorkGrouper.group(
            uniqueFiles.map { file ->
                TelegramWorkGrouper.Input(
                    fileId = file.fileId,
                    title = titles.getValue(file.fileId),
                    season = file.season,
                    number = file.episodeNumber,
                    isDocument = file.mediaKind == TelegramMediaKind.DOCUMENT,
                )
            },
        )

        val byFile = uniqueFiles.associateBy { it.fileId }

        val groups = grouping.groupBy { it.title to it.season }.map { (key, members) ->
            val (baseTitle, season) = key
            val mediaKind = byFile.getValue(members.first().fileId).mediaKind
            val previous = previousWorks[workId(chatId, baseTitle, season)]

            val metadata = when {
                previous != null && previous.anilistId != null -> {
                    TelegramResolvedWork(
                        query = previous.baseTitle,
                        anilistId = previous.anilistId,
                        title = previous.title,
                        coverUrl = previous.coverUrl,
                        description = previous.description,
                        genres = previous.genres,
                        year = previous.year,
                        totalEpisodes = previous.totalEpisodes,
                        status = previous.status,
                        score = previous.matchScore,
                        candidates = previous.candidates,
                        resolvedAt = 1L,
                    )
                }
                !resolveRemote && previous != null -> {
                    TelegramResolvedWork(
                        query = previous.baseTitle,
                        anilistId = previous.anilistId,
                        title = previous.title,
                        coverUrl = previous.coverUrl,
                        description = previous.description,
                        genres = previous.genres,
                        year = previous.year,
                        totalEpisodes = previous.totalEpisodes,
                        status = previous.status,
                        score = previous.matchScore,
                        candidates = previous.candidates,
                        resolvedAt = 1L,
                    )
                }
                resolveRemote -> resolver.resolve(
                    title = baseTitle,
                    season = season,
                    kind = mediaKind,
                    forcedTitle = forced,
                )
                else -> TelegramResolvedWork(query = baseTitle, title = baseTitle)
            }

            GroupedWork(
                title = baseTitle,
                season = season,
                mediaKind = mediaKind,
                fileIds = members.map { it.fileId },
                metadata = metadata,
            )
        }

        // Deux groupes qui pointent vers la même entrée AniList sont la même
        // œuvre : Black Clover S2 et les épisodes 166-170, par exemple.
        val merged = groups
            .groupBy { group -> group.metadata.anilistId?.let { group.mediaKind to it } }
            .values
            .flatMap { sameWork ->
                if (sameWork.size == 1 || sameWork.first().metadata.anilistId == null) {
                    sameWork
                } else {
                    val dominant = sameWork.maxBy { it.fileIds.size }
                    listOf(
                        dominant.copy(
                            season = sameWork.map { it.season }.distinct().singleOrNull(),
                            fileIds = sameWork.flatMap { it.fileIds },
                        ),
                    )
                }
            }

        val workIds = HashMap<Int, String>()
        val works = merged.map { group ->
            val id = workId(chatId, group.title, group.season)
            group.fileIds.forEach { workIds[it] = id }

            val title = group.metadata.title?.takeIf { it.isNotBlank() } ?: group.title

            TelegramWorkEntry(
                id = id,
                chatId = chatId,
                mediaKind = group.mediaKind,
                title = title,
                baseTitle = group.title,
                season = group.season,
                anilistId = group.metadata.anilistId,
                coverUrl = group.metadata.coverUrl,
                description = group.metadata.description,
                genres = group.metadata.genres,
                year = group.metadata.year,
                totalEpisodes = group.metadata.totalEpisodes,
                status = group.metadata.status,
                matchScore = if (group.metadata.failed) -1.0 else group.metadata.score,
                candidates = group.metadata.candidates,
                fileCount = group.fileIds.size,
                episodeCount = group.fileIds
                    .mapNotNull { byFile[it]?.episodeNumber }
                    .distinct()
                    .size,
                lastFileDate = group.fileIds.maxOfOrNull { byFile[it]?.date ?: 0L } ?: 0L,
            )
        }.filter { it.fileCount > 0 }

        val indexed = uniqueFiles.map { file ->
            workIds[file.fileId]?.let { file.copy(workId = it) } ?: file
        }

        return works to indexed
    }

    private data class GroupedWork(
        val title: String,
        val season: Int?,
        val mediaKind: TelegramMediaKind,
        val fileIds: List<Int>,
        val metadata: TelegramResolvedWork,
    )

    private fun workId(chatId: Long, title: String, season: Int?): String {
        return "$chatId:${TelegramTitleParser.slug(title)}:${season ?: 0}"
    }

    companion object {
        /** Budget de rétro-remplissage par synchronisation. */
        const val BACKFILL_MESSAGE_LIMIT = 5000
        const val INCREMENTAL_MESSAGE_LIMIT = 2000

        /** À incrémenter dès que le parseur ou le regroupement change. */
        const val INDEX_VERSION = 17

        /** Dernière version nécessitant une reconstruction complète des fichiers. */
        const val LEGACY_REBUILD_VERSION = 17

        /** Un canal bloqué ne doit pas figer la synchro globale. */
        const val SYNC_TIMEOUT_MS = 90_000L

        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "m2ts")
        val DOC_EXTENSIONS = setOf("cbz", "zip", "pdf", "epub", "rar", "7z", "cbr")
        val DOC_MIME_TYPES = setOf(
            "application/pdf",
            "application/zip",
            "application/x-cbz",
            "application/vnd.comicbook+zip",
            "application/epub+zip",
        )
    }
}

data class TelegramSyncSummary(
    val channel: TelegramChannelIndex,
    val newFiles: Int,
    val newEpisodes: Int,
    val newWorks: List<String>,
    val messagesScanned: Int = 0,
    val historyComplete: Boolean = false,
)

sealed interface TelegramSyncState {
    data object Idle : TelegramSyncState

    data class Syncing(
        val chatId: Long,
        val title: String,
        val current: Int,
        val total: Int,
    ) : TelegramSyncState {
        val progress: Float
            get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Error(val message: String) : TelegramSyncState
}
