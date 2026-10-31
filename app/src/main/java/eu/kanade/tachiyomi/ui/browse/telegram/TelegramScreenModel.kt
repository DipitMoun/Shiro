package eu.kanade.tachiyomi.ui.browse.telegram

import android.app.Application
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.media.EpisodeMediaValidator
import eu.kanade.tachiyomi.data.telegram.TelegramFileHeuristics
import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.index.TelegramChannelSummary
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexManager
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexedFile
import eu.kanade.tachiyomi.data.telegram.index.TelegramLibraryImporter
import eu.kanade.tachiyomi.data.telegram.index.TelegramMediaKind
import eu.kanade.tachiyomi.data.telegram.index.TelegramResolvedWork
import eu.kanade.tachiyomi.data.telegram.index.TelegramSyncJob
import eu.kanade.tachiyomi.data.telegram.index.TelegramSyncState
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkOverrideStore
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkOverrides
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkResolver
import eu.kanade.tachiyomi.data.telegram.index.aggregationKey
import eu.kanade.tachiyomi.data.telegram.index.countsWith
import eu.kanade.tachiyomi.data.telegram.index.episodeKey
import eu.kanade.tachiyomi.data.telegram.index.identityKey
import eu.kanade.tachiyomi.data.telegram.index.isUnresolved
import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import eu.kanade.tachiyomi.data.telegram.saved.TelegramExternalMetadataResolver
import eu.kanade.tachiyomi.data.telegram.saved.TelegramMetadataProvider
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedFile
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWork
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkManager
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import eu.kanade.tachiyomi.data.telegram.saved.mergeSavedFiles
import eu.kanade.tachiyomi.data.telegram.saved.removeSavedFiles
import eu.kanade.tachiyomi.data.telegram.saved.stableKey
import eu.kanade.tachiyomi.data.telegram.selectedTelegramChatIds
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthManager
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramChannelScraper
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramFileKind
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramFileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * État de l'onglet Explorer « Telegram ».
 */
data class TelegramUiState(
    val authState: TelegramAuthState = TelegramAuthState.Starting,
    val channels: List<TelegramChannelUi> = emptyList(),
    val enabledChatIds: Set<Long> = emptySet(),
    val works: List<TelegramWorkUi> = emptyList(),
    val unresolvedWorks: List<TelegramWorkUi> = emptyList(),
    val omdbApiKey: String = "",
    val chats: List<TelegramChatItem> = emptyList(),
    val videoChats: Set<Long> = emptySet(),
    val docChats: Set<Long> = emptySet(),
    val loadingChats: Boolean = false,
    val syncState: TelegramSyncState = TelegramSyncState.Idle,
    val busy: Boolean = false,
    val message: String? = null,
    // Recherche
    val searchQuery: String = "",
    val searchGroups: List<TelegramSearchGroupUi> = emptyList(),
    val searchBusy: Boolean = false,
    val searchDone: Boolean = false,
    val searchProgressChannel: String = "",
    val searchProgressDone: Int = 0,
    val searchProgressTotal: Int = 0,
    val searchHasMore: Boolean = false,
    val searchLimit: Int = 200,
    val searchErrors: List<String> = emptyList(),
    val searchLocalMatches: Int = 0,
    val searchServerMatches: Int = 0,
    val searchIndexedChannels: Int = 0,
    // Œuvres validées
    val savedWorks: List<TelegramSavedWork> = emptyList(),
    /**
     * Incrémenté à chaque relecture de l'index. Les écrans de détail s'en
     * servent comme clé de recalcul : `state.channels` peut être structurellement
     * égal alors que les overrides (reconnaissance, fusion) ont changé.
     */
    val revision: Long = 0L,
)

data class TelegramChatItem(
    val id: Long,
    val title: String,
    val isChannel: Boolean,
)

data class TelegramChannelUi(
    val chatId: Long,
    val title: String,
    val videoWorks: Int,
    val docWorks: Int,
    val videoFiles: Int,
    val docFiles: Int,
    val unknownVideo: Int,
    val unknownDoc: Int,
    val lastSyncAt: Long,
    val forcedWork: String?,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val previewCovers: List<String> = emptyList(),
    val messagesScanned: Int = 0,
    val historyComplete: Boolean = false,
    val isMember: Boolean? = null,
    val isForum: Boolean = false,
) {
    val workCount: Int
        get() = videoWorks + docWorks

    val fileCount: Int
        get() = videoFiles + docFiles

    val unknownCount: Int
        get() = unknownVideo + unknownDoc
}

data class TelegramWorkUi(
    val id: String,
    val chatId: Long = 0L,
    val title: String,
    val kind: String,
    val files: Int,
    val episodes: Int,
    val resolved: Boolean,
    val coverUrl: String? = null,
    val lastFileDate: Long = 0L,
    val chatIds: List<Long> = emptyList(),
    val workKey: String = "",
    /** Clé avant résolution de fusion, utilisée pour distinguer deux œuvres différentes. */
    val rawKey: String = "",
    val fileKeys: List<String> = emptyList(),
    val episodeKeys: List<String> = emptyList(),
)

/** Groupe de résultats de recherche, corrigeable à la main avant validation. */
data class TelegramSearchGroupUi(
    val id: String,
    val groupKey: String,
    val title: String,
    val kind: TelegramMediaKind,
    val files: List<TelegramSearchFileUi>,
    val savedWorkId: String? = null,
    /** Vrai si au moins un fichier du groupe provient de l'index local. */
    val indexed: Boolean = false,
    val coverUrl: String? = null,
) {
    val fileCount: Int
        get() = files.size

    val episodeCount: Int
        get() = files
            .filter { it.episode != null }
            .map { (it.season ?: 1) to it.episode!! }
            .distinct()
            .size

    /** Clés des fichiers qui ont au moins un doublon dans le groupe. */
    val duplicateKeys: Set<String>
        get() = files.groupBy { it.duplicateKey }
            .filterValues { it.size > 1 }
            .values
            .flatten()
            .map { it.key }
            .toSet()
}

data class TelegramSearchFileUi(
    val chatId: Long,
    val chatTitle: String,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val caption: String?,
    val date: Long,
    val threadId: Long,
    val kind: TelegramMediaKind,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val episodeEnd: Int?,
    val chapter: Int?,
    val chapterEnd: Int?,
    val language: String?,
    val variant: String?,
    /** Clé d'agrégation de l'œuvre indexée, null si le fichier n'est pas indexé. */
    val indexedWorkKey: String? = null,
    val coverUrl: String? = null,
) {
    /** Identifiant stable dans les résultats (les messageId se répètent d'un canal à l'autre). */
    val key: String
        get() = "$chatId:$messageId"

    val displayName: String
        get() = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

    val duplicateKey: String
        get() = listOf(season, episode, language, variant, displayName.lowercase()).joinToString("|")
}

private const val SAVED_WORKS_REPAIR_VERSION = 1

class TelegramScreenModel : ScreenModel {

    private val appContext: Application by injectLazy()
    private val authManager: TelegramAuthManager by injectLazy()
    private val scraper: TelegramChannelScraper by injectLazy()
    private val preferences: TelegramPreferences by injectLazy()
    private val indexManager: TelegramIndexManager by injectLazy()
    private val importer: TelegramLibraryImporter by injectLazy()
    private val savedStore: TelegramSavedWorkStore by injectLazy()
    private val savedWorkManager: TelegramSavedWorkManager by injectLazy()
    private val workOverrides: TelegramWorkOverrideStore by injectLazy()
    private val workResolver: TelegramWorkResolver by injectLazy()
    private val externalResolver: TelegramExternalMetadataResolver by injectLazy()

    private val _state = MutableStateFlow(TelegramUiState())
    val state: StateFlow<TelegramUiState> = _state.asStateFlow()

    /**
     * Recherche en cours. Une nouvelle requête ou un changement de texte annule
     * la précédente : sans cela, les résultats d'une ancienne requête pouvaient
     * réapparaître après un effacement ou un mot différent.
     */
    private var searchJob: Job? = null

    init {
        authManager.start()

        // La migration des résumés peut décoder les anciens index une seule
        // fois : elle ne doit jamais bloquer le thread principal.
        screenModelScope.launch {
            withContext(Dispatchers.IO) { refresh() }
        }

        // Un index produit par un ancien parseur est corrigé sans rescanner Telegram.
        screenModelScope.launch {
            // Nettoyages indépendants de l'index : ils doivent passer tout de suite.
            runCatching { savedStore.removeSuspectFiles() }
                .onSuccess {
                    logcat(LogPriority.INFO) { "Nettoyage des œuvres suspectes: $it" }
                    if (it) refresh()
                }
                .onFailure { logcat(LogPriority.WARN, it) { "Nettoyage des œuvres impossible" } }

            if (preferences.savedWorksRepairVersion < SAVED_WORKS_REPAIR_VERSION) {
                runCatching { savedWorkManager.reanalyzeAll() }
                    .onSuccess { changed ->
                        if (changed > 0) {
                            logcat(LogPriority.INFO) { "Œuvres Telegram ré-analysées: $changed" }
                        }
                    }
                    .onFailure { logcat(LogPriority.WARN, it) { "Ré-analyse des œuvres impossible" } }

                runCatching { importer.refreshTelegramEpisodeLists() }
                    .onSuccess {
                        preferences.savedWorksRepairVersion = SAVED_WORKS_REPAIR_VERSION
                        logcat(LogPriority.INFO) { "Listes d'épisodes Telegram marquées à recharger" }
                    }
                    .onFailure {
                        logcat(LogPriority.WARN, it) { "Réinitialisation des listes Telegram impossible" }
                    }
                refresh()
            }

            runCatching { importer.removeGenericWorks() }
                .onFailure { logcat(LogPriority.WARN, it) { "Nettoyage des entrées génériques impossible" } }

            if (!preferences.episodeUrlsMigrated) {
                runCatching { importer.refreshTelegramEpisodeLists() }
                    .onSuccess { preferences.episodeUrlsMigrated = true }
                    .onFailure {
                        logcat(LogPriority.WARN, it) { "Migration des URLs d'épisodes impossible" }
                    }
            }

            // Les entrées Telegram existantes sont regroupées dans « Telegram ».
            runCatching { importer.migrateToTelegramCategory() }
                .onFailure { logcat(LogPriority.WARN, it) { "Migration des collections impossible" } }

            // Une seule réparation locale complète après changement de parseur.
            // Elle traite les canaux un par un pour limiter la mémoire.
            if (preferences.indexRepairVersion < TelegramIndexManager.INDEX_VERSION) {
                runCatching { indexManager.rebuildAllIfNeeded() }
                    .onSuccess { rebuilt ->
                        preferences.indexRepairVersion = TelegramIndexManager.INDEX_VERSION
                        logcat(LogPriority.INFO) { "Réparation locale Telegram : $rebuilt canaux" }
                        refresh()
                    }
                    .onFailure { logcat(LogPriority.WARN, it) { "Réparation locale des index impossible" } }
            }
        }

        screenModelScope.launch {
            authManager.authState.collect { auth ->
                _state.update { it.copy(authState = auth) }
                if (auth is TelegramAuthState.Ready) {
                    loadChatsIfNeeded()
                }
            }
        }

        screenModelScope.launch {
            indexManager.syncState.collect { sync ->
                _state.update { it.copy(syncState = sync) }
            }
        }

        screenModelScope.launch {
            val checked = mutableSetOf<String>()
            savedStore.works.collect { works ->
                _state.update { it.copy(savedWorks = works) }

                // Renseigne l'identifiant de bibliothèque des œuvres créées
                // avant l'ajout de ce champ (ou dont l'import avait échoué).
                works.filter { it.libraryId == null && checked.add(it.id) }
                    .forEach { work ->
                        runCatching { importer.libraryIdOf(work) }
                            .getOrNull()
                            ?.let { id -> savedStore.save(work.copy(libraryId = id)) }
                    }
            }
        }
    }

    // ------------------------------------------------------------------
    // Authentification
    // ------------------------------------------------------------------

    fun sendPhoneNumber(phone: String) = authManager.sendPhoneNumber(phone)
    fun sendCode(code: String) = authManager.sendAuthCode(code)

    fun checkPassword(password: String) = authManager.checkPassword(password)

    fun resendCode() = authManager.resendCode()

    fun retry() = authManager.restart()

    fun logout() {
        screenModelScope.launch {
            authManager.logoutAndWipe()
            preferences.removeAll()
            TelegramSyncJob.setupTask(appContext)
            refresh()
        }
    }

    // ------------------------------------------------------------------
    // Canaux
    // ------------------------------------------------------------------

    fun loadChats() {
        if (_state.value.loadingChats) return
        screenModelScope.launch {
            _state.update { it.copy(loadingChats = true) }
            val chats = runCatching { scraper.getUserChats() }
                .onFailure { logcat(LogPriority.WARN, it) { "Chargement des canaux impossible" } }
                .getOrDefault(emptyList())
                .map { TelegramChatItem(it.id, it.title, it.isChannel) }
                .sortedBy { it.title.lowercase() }

            _state.update { it.copy(chats = chats, loadingChats = false) }
        }
    }

    private fun loadChatsIfNeeded() {
        if (_state.value.chats.isEmpty()) {
            loadChats()
        }
    }

    fun saveSelection(videoChats: Set<Long>, docChats: Set<Long>) {
        val previous = preferences.allChatIds
        preferences.videoChats = videoChats
        preferences.docChats = docChats
        preferences.disabledChats = preferences.disabledChats - videoChats - docChats

        videoChats.forEach { id -> rememberTitle(id) }
        docChats.forEach { id -> rememberTitle(id) }

        (previous - videoChats - docChats).forEach { id ->
            // Canal retiré du suivi : on garde l'index pour éviter de tout rescanner.
            preferences.forgetChannel(id)
        }

        TelegramSyncJob.setupTask(appContext)
        refresh()

        _state.update { it.copy(message = "Sélection enregistrée") }
    }

    private fun rememberTitle(chatId: Long) {
        if (preferences.channelTitle(chatId) != null) return
        val known = _state.value.chats.firstOrNull { it.id == chatId }?.title
        if (known != null) {
            preferences.rememberChannel(chatId, known)
        }
    }

    fun setForcedWork(chatId: Long, title: String?) {
        preferences.setForcedWork(chatId, title)
        refresh()
    }

    fun removeChannel(chatId: Long) {
        indexManager.removeChannel(chatId)
        refresh()
    }

    fun setOmdbApiKey(apiKey: String) {
        preferences.omdbApiKey = apiKey
        _state.update { it.copy(omdbApiKey = preferences.omdbApiKey) }
    }

    fun isChannelEnabled(chatId: Long): Boolean = chatId !in preferences.disabledChats

    fun setChannelEnabled(chatId: Long, enabled: Boolean) {
        preferences.disabledChats = if (enabled) {
            preferences.disabledChats - chatId
        } else {
            preferences.disabledChats + chatId
        }
        TelegramSyncJob.setupTask(appContext)
        refresh()
    }

    /** Synchronise le canal au moment où l'utilisateur entre dans sa collection. */
    fun syncChannelOnOpen(chatId: Long) {
        if (_state.value.busy) return
        val lastSync = _state.value.channels.firstOrNull { it.chatId == chatId }?.lastSyncAt ?: 0L
        val recent = lastSync > 0L && System.currentTimeMillis() - lastSync < CHANNEL_SYNC_COOLDOWN_MS

        screenModelScope.launch {
            // Applique les correctifs locaux (regroupement d'épisodes) même
            // quand la synchro réseau est encore en cooldown.
            val rebuilt = runCatching { indexManager.rebuildChannel(chatId) }
                .onFailure { logcat(LogPriority.WARN, it) { "Reconstruction locale du canal $chatId impossible" } }
                .getOrDefault(0)
            if (rebuilt > 0) refresh()
            if (recent) return@launch

            _state.update { it.copy(busy = true) }
            // À l'ouverture, on ne fait que le delta récent. Le backfill complet
            // reste une action explicite depuis l'écran du canal.
            runCatching { indexManager.syncChannel(chatId, allowBackfill = false) }
                .onFailure { logcat(LogPriority.WARN, it) { "Synchronisation du canal $chatId impossible" } }
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    /** Synchronisation explicite d'un canal depuis son menu d'actions. */
    fun syncChannelNow(chatId: Long) {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = runCatching {
                val summary = indexManager.syncChannel(chatId, allowBackfill = false)
                "${summary.newFiles} nouveau(x) fichier(s) indexé(s)"
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Synchronisation du canal $chatId impossible" } }
                .getOrElse { it.message ?: "Synchronisation impossible" }
            refresh()
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    // ------------------------------------------------------------------
    // Recherche
    // ------------------------------------------------------------------

    fun setSearchQuery(query: String) {
        val changed = query != _state.value.searchQuery
        if (changed) {
            searchJob?.cancel()
            searchJob = null
        }
        _state.update {
            it.copy(
                searchQuery = query,
                searchBusy = if (changed) false else it.searchBusy,
                searchDone = if (changed) false else it.searchDone,
                searchGroups = if (changed) emptyList() else it.searchGroups,
                searchHasMore = if (changed) false else it.searchHasMore,
                searchErrors = if (changed) emptyList() else it.searchErrors,
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _state.update {
            it.copy(
                searchQuery = "",
                searchGroups = emptyList(),
                searchDone = false,
                searchBusy = false,
                searchErrors = emptyList(),
                searchHasMore = false,
                searchLimit = SEARCH_PAGE_SIZE,
                searchProgressDone = 0,
                searchProgressTotal = 0,
                searchProgressChannel = "",
                searchLocalMatches = 0,
                searchServerMatches = 0,
                searchIndexedChannels = 0,
            )
        }
    }

    /** Lance la recherche sur les canaux ajoutés. Entrée ou bouton uniquement. */
    fun search() = startSearch(resetLimit = true)

    /** Élargit la recherche serveur (200 fichiers par canal supplémentaires). */
    fun continueSearch() = startSearch(resetLimit = false)

    private fun startSearch(resetLimit: Boolean) {
        val query = _state.value.searchQuery.trim()
        if (query.isEmpty() || _state.value.searchBusy) return
        // Jamais tout Telegram : uniquement les canaux explicitement suivis
        // (mode Vidéo ou Documents) et non désactivés par l'utilisateur.
        val selectedChatIds = selectedTelegramChatIds(
            videoChats = preferences.videoChats,
            docChats = preferences.docChats,
            disabledChats = preferences.disabledChats,
        ).intersect(preferences.allChatIds)
        if (selectedChatIds.isEmpty()) {
            _state.update { it.copy(message = "Ajoutez d'abord un canal") }
            return
        }

        val limit = if (resetLimit) {
            SEARCH_PAGE_SIZE
        } else {
            (_state.value.searchLimit + SEARCH_PAGE_SIZE).coerceAtMost(MAX_SEARCH_LIMIT)
        }
        if (!resetLimit && limit <= _state.value.searchLimit) return

        searchJob?.cancel()
        searchJob = screenModelScope.launch {
            val chatIds = selectedChatIds.toList()
            val tokens = searchTokens(query)
            val overrideSnapshot = workOverrides.snapshot()
            _state.update {
                it.copy(
                    searchBusy = true,
                    searchDone = false,
                    searchGroups = emptyList(),
                    searchErrors = emptyList(),
                    searchHasMore = false,
                    searchLimit = limit,
                    searchProgressDone = 0,
                    searchProgressTotal = chatIds.size,
                    searchProgressChannel = "",
                    searchLocalMatches = 0,
                    searchServerMatches = 0,
                    searchIndexedChannels = 0,
                )
            }

            val groups = mutableListOf<TelegramSearchGroupUi>()
            val errors = mutableListOf<String>()
            var hasMore = false
            var done = 0
            var localMatches = 0
            var serverMatches = 0
            var indexedChannels = 0

            for (chatId in chatIds) {
                val channelTitle = preferences.channelTitle(chatId)
                    ?: indexManager.channel(chatId)?.title
                    ?: "Canal $chatId"
                _state.update { it.copy(searchProgressChannel = channelTitle) }

                // 1. Index local : œuvres déjà indexées, avec couverture et clé
                //    de fusion. C'est la seule source fiable pour les noms.
                val indexed = indexManager.channel(chatId)
                val localFiles = withContext(Dispatchers.Default) {
                    indexed?.files
                        ?.filter { it.mediaKind == TelegramMediaKind.VIDEO }
                        ?.filter { it.matchesSearch(tokens) }
                        ?.filterNot {
                            TelegramFileHeuristics.isUnsupportedFile(it.fileName, it.mimeType) ||
                                TelegramFileHeuristics.isSuspect(it.fileName, it.caption, it.title)
                        }
                        .orEmpty()
                }
                if (indexed != null) indexedChannels++
                localMatches += localFiles.size
                localFiles.forEach { file ->
                    val work = indexed?.works?.firstOrNull { it.id == file.workId }
                    val workKey = work?.aggregationKey()?.let(overrideSnapshot::resolve)
                    addToGroups(
                        groups,
                        file.toSearchFile(
                            chatTitle = indexed?.title ?: channelTitle,
                            indexedWorkKey = workKey,
                            coverUrl = workKey
                                ?.let(overrideSnapshot::coverFor)
                                ?: work?.coverUrl,
                        ),
                    )
                }

                // 2. Recherche serveur : uniquement pour les canaux dont
                //    l'index n'est pas encore complet (sinon l'index local est
                //    la vérité et la recherche reste instantanée). Les fichiers
                //    trouvés ici sont précisément les « non indexés ».
                val result = if (indexed?.historyComplete == true) {
                    null
                } else {
                    runCatching { scraper.searchFiles(chatId, query, limit) }
                        .onFailure {
                            logcat(LogPriority.WARN, it) { "Recherche Telegram impossible ($chatId)" }
                            errors += "$channelTitle : ${it.message ?: "recherche impossible"}"
                        }
                        .getOrNull()
                }

                result?.files?.forEach { metadata ->
                    metadata.toSearchFile(chatId, channelTitle)
                        ?.takeIf { it.kind == TelegramMediaKind.VIDEO }
                        ?.let { file ->
                            addToGroups(groups, file)
                            serverMatches++
                        }
                }
                if (result != null) hasMore = hasMore || result.hasMore

                done++
                _state.update {
                    it.copy(
                        searchProgressDone = done,
                        searchGroups = groups.sortedForDisplay(),
                        searchErrors = errors.toList(),
                        searchHasMore = hasMore,
                        searchLocalMatches = localMatches,
                        searchServerMatches = serverMatches,
                        searchIndexedChannels = indexedChannels,
                    )
                }
            }

            _state.update {
                it.copy(
                    searchBusy = false,
                    searchDone = true,
                    searchGroups = groups.sortedForDisplay(),
                    searchErrors = errors,
                    searchHasMore = hasMore,
                    searchProgressChannel = "",
                    searchLocalMatches = localMatches,
                    searchServerMatches = serverMatches,
                    searchIndexedChannels = indexedChannels,
                )
            }
        }
    }

    fun renameGroup(groupId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        _state.update { state ->
            val group = state.searchGroups.firstOrNull { it.id == groupId } ?: return@update state
            if (group.savedWorkId != null) return@update state
            state.copy(
                searchGroups = state.searchGroups.map {
                    if (it.id == groupId) it.copy(title = clean) else it
                },
            )
        }
    }

    /** Fusionne des groupes sélectionnés (même type uniquement). */
    fun mergeGroups(groupIds: Set<String>) {
        _state.update { state ->
            val selected = state.searchGroups.filter { it.id in groupIds }
            if (selected.size < 2) return@update state
            if (selected.any { it.savedWorkId != null }) return@update state
            if (selected.map { it.kind }.distinct().size != 1) return@update state

            val target = selected.minByOrNull { it.title.lowercase() } ?: return@update state
            val files = selected.flatMap { it.files }
                .distinctBy { it.key }
                .sortedForFiles()

            state.copy(
                searchGroups = (
                    state.searchGroups.filterNot { it.id in groupIds } +
                        target.copy(
                            files = files,
                            indexed = selected.any { it.indexed },
                            coverUrl = selected.firstNotNullOfOrNull { it.coverUrl },
                        )
                    ).sortedForDisplay(),
            )
        }
    }

    /** Sépare des fichiers d'un groupe, en recréant un groupe par œuvre reconnue. */
    fun splitFiles(groupId: String, fileKeys: Set<String>) {
        if (fileKeys.isEmpty()) return
        _state.update { state ->
            val group = state.searchGroups.firstOrNull { it.id == groupId } ?: return@update state
            if (group.savedWorkId != null) return@update state

            val selected = group.files.filter { it.key in fileKeys }
            val remaining = group.files.filterNot { it.key in fileKeys }
            if (selected.isEmpty() || remaining.isEmpty()) return@update state

            val newGroups = selected
                .groupBy {
                    "${it.kind.name}:${TelegramTitleParser.signature(it.title.ifBlank { it.displayName })}"
                }
                .map { (key, files) ->
                    TelegramSearchGroupUi(
                        id = UUID.randomUUID().toString(),
                        groupKey = key,
                        title = files.first().title.ifBlank { files.first().displayName }.toDisplayTitle(),
                        kind = files.first().kind,
                        files = files.sortedForFiles(),
                        indexed = files.any { it.indexedWorkKey != null },
                        coverUrl = files.firstNotNullOfOrNull { it.coverUrl },
                    )
                }

            state.copy(
                searchGroups = (
                    state.searchGroups.filterNot { it.id == groupId } +
                        group.copy(files = remaining) +
                        newGroups
                    ).sortedForDisplay(),
            )
        }
    }

    /** Exclut un fichier des résultats (mauvaise correspondance ou doublon). */
    fun removeFile(groupId: String, fileKey: String) {
        _state.update { state ->
            val group = state.searchGroups.firstOrNull { it.id == groupId } ?: return@update state
            if (group.savedWorkId != null) return@update state

            val remaining = group.files.filterNot { it.key == fileKey }
            val groups = if (remaining.isEmpty()) {
                state.searchGroups.filterNot { it.id == groupId }
            } else {
                state.searchGroups.map {
                    if (it.id == groupId) it.copy(files = remaining) else it
                }
            }
            state.copy(searchGroups = groups)
        }
    }

    /** Valide le groupe courant : stockage local + entrée de bibliothèque. */
    fun createWork(groupId: String) {
        val group = _state.value.searchGroups.firstOrNull { it.id == groupId } ?: return
        if (group.savedWorkId != null || _state.value.busy) return

        screenModelScope.launch {
            _state.update { it.copy(busy = true) }

            val validFiles = group.files.filterNot {
                TelegramFileHeuristics.isSuspect(it.fileName, it.caption, group.title)
            }
            if (validFiles.isEmpty()) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = "Aucun fichier valide dans ce groupe (exports génériques ignorés)",
                    )
                }
                return@launch
            }

            val displayTitle = group.title.toDisplayTitle().ifBlank { "Sans titre" }
            val work = TelegramSavedWork(
                id = UUID.randomUUID().toString(),
                title = displayTitle,
                kind = if (group.kind == TelegramMediaKind.VIDEO) {
                    TelegramSavedKind.VIDEO
                } else {
                    TelegramSavedKind.DOCUMENT
                },
                files = validFiles.map { it.toSavedFile() },
                createdAt = System.currentTimeMillis(),
                sourceTitle = group.title,
            )

            val outcome = runCatching {
                val libraryId = importer.importSavedWork(work)
                if (libraryId != null) {
                    savedStore.save(work.copy(libraryId = libraryId))
                    true
                } else {
                    false
                }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Création de l'œuvre Telegram impossible" }
            }

            val success = outcome.getOrDefault(false)
            val message = outcome.exceptionOrNull()?.message
                ?: if (success) "« ${work.title} » ajoutée à la bibliothèque" else "Création impossible"

            _state.update { state ->
                state.copy(
                    busy = false,
                    message = message,
                    searchGroups = if (success) {
                        state.searchGroups.map {
                            if (it.id == groupId) it.copy(savedWorkId = work.id) else it
                        }
                    } else {
                        state.searchGroups
                    },
                )
            }

            // AniList arrive après coup : la création n'attend jamais le réseau.
            if (success) {
                val saved = savedStore.find(work.id) ?: return@launch
                runCatching { savedWorkManager.resolve(saved) }
                    .onFailure {
                        logcat(LogPriority.WARN, it) { "Résolution AniList impossible" }
                    }
            }
        }
    }

    // ------------------------------------------------------------------
    // Gestion des œuvres validées
    // ------------------------------------------------------------------

    /**
     * Ajoute des fichiers sélectionnés à une œuvre validée manuellement.
     *
     * L'index Telegram reste en lecture seule : il est reconstruit à partir
     * des messages du canal, et y injecter un fichier manuel ferait perdre
     * l'entrée au prochain scan. Les œuvres validées, elles, possèdent une
     * liste de fichiers explicite et persistante : c'est donc la seule cible
     * où l'ajout est sûr.
     */
    fun addFilesToSavedWork(workId: String, files: List<TelegramSearchFileUi>) {
        val work = savedStore.find(workId) ?: return
        if (files.isEmpty()) {
            notify("Aucun fichier sélectionné")
            return
        }
        val kinds = files.map { it.kind }.distinct()
        if (kinds.size > 1) {
            notify("Sélection mixte : choisis des fichiers vidéo ou documents, pas les deux.")
            return
        }
        val expectedKind = if (work.kind == TelegramSavedKind.VIDEO) {
            TelegramMediaKind.VIDEO
        } else {
            TelegramMediaKind.DOCUMENT
        }
        if (kinds.single() != expectedKind) {
            notify(
                if (expectedKind == TelegramMediaKind.VIDEO) {
                    "« ${work.title} » accepte uniquement des fichiers vidéo."
                } else {
                    "« ${work.title} » accepte uniquement des documents."
                },
            )
            return
        }
        val additions = files.map { it.toSavedFile() }
        val updatedFiles = mergeSavedFiles(work.files, additions)
        if (updatedFiles.size == work.files.size) {
            notify("Ces fichiers sont déjà dans « ${work.title} »")
            return
        }
        screenModelScope.launch {
            val updated = work.copy(files = updatedFiles)
            runCatching {
                savedStore.save(updated)
                importer.updateSavedWorkEntry(updated, resetInitialized = true)
            }
                .onSuccess {
                    val added = updated.files.size - work.files.size
                    notify("$added fichier(s) ajouté(s) à « ${work.title} »")
                }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Ajout de fichiers Telegram impossible" }
                    notify(it.message ?: "Ajout impossible")
                }
        }
    }

    /**
     * Retire des fichiers d'une œuvre validée. Refuse de vider entièrement
     * l'œuvre : une suppression complète passe par l'action « Supprimer ».
     */
    fun removeFilesFromSavedWork(workId: String, files: List<TelegramSearchFileUi>) {
        val work = savedStore.find(workId) ?: return
        if (files.isEmpty()) {
            notify("Aucun fichier sélectionné")
            return
        }
        val keys = files.mapTo(mutableSetOf()) { it.key }
        // Les clés de recherche sont "chatId:messageId", identiques aux
        // fichiers validés. On filtre aussi les fichiers absents de l'œuvre.
        val remaining = removeSavedFiles(
            work.files,
            work.files.filter { it.stableKey() in keys }.map { it.stableKey() }.toSet(),
        )
        if (remaining.size == work.files.size) {
            notify("Aucun fichier sélectionné n'appartient à « ${work.title} »")
            return
        }
        if (remaining.isEmpty()) {
            notify("Retrait refusé : l'œuvre deviendrait vide. Utilisez Supprimer.")
            return
        }
        screenModelScope.launch {
            val updated = work.copy(files = remaining)
            runCatching {
                savedStore.save(updated)
                importer.updateSavedWorkEntry(updated, resetInitialized = true)
            }
                .onSuccess {
                    val removed = work.files.size - updated.files.size
                    notify("$removed fichier(s) retiré(s) de « ${work.title} »")
                }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Retrait de fichiers Telegram impossible" }
                    notify(it.message ?: "Retrait impossible")
                }
        }
    }

    fun notify(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun renameWork(workId: String, title: String) {
        val work = savedStore.find(workId) ?: return
        screenModelScope.launch {
            runCatching { savedWorkManager.rename(work, title) }
                .onSuccess { _state.update { it.copy(message = "Œuvre renommée") } }
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) { "Renommage Telegram impossible" }
                    _state.update { it.copy(message = error.message ?: "Renommage impossible") }
                }
        }
    }

    fun resyncWork(workId: String) {
        val work = savedStore.find(workId) ?: return
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { savedWorkManager.refresh(work) }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Resynchronisation de l'œuvre impossible" }
                }
            val updated = result.getOrNull()
            val added = (updated?.files?.size ?: work.files.size) - work.files.size
            _state.update {
                it.copy(
                    busy = false,
                    message = result.exceptionOrNull()?.message
                        ?: if (added > 0) "+$added fichier(s) ajouté(s)" else "Aucun nouveau fichier",
                )
            }
            if (added > 0) {
                savedStore.find(workId)?.let { refreshed ->
                    runCatching { savedWorkManager.resolve(refreshed) }
                }
            }
        }
    }

    fun deleteWork(workId: String) {
        val work = savedStore.find(workId) ?: return
        screenModelScope.launch {
            runCatching { savedWorkManager.delete(work) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            message = "« ${work.title} » supprimée",
                            searchGroups = it.searchGroups.map { group ->
                                if (group.savedWorkId == workId) group.copy(savedWorkId = null) else group
                            },
                        )
                    }
                }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Suppression Telegram impossible" }
                    _state.update { it.copy(message = "Suppression impossible") }
                }
        }
    }

    fun setWatch(workId: String, enabled: Boolean, intervalMinutes: Int) {
        val work = savedStore.find(workId) ?: return
        screenModelScope.launch {
            runCatching { savedWorkManager.setWatch(work, enabled, intervalMinutes) }
                .onSuccess {
                    TelegramSyncJob.setupTask(appContext)
                    _state.update {
                        it.copy(
                            message = if (enabled) {
                                "Veille activée (${formatInterval(intervalMinutes)})"
                            } else {
                                "Veille désactivée"
                            },
                        )
                    }
                    if (enabled) {
                        // Première vérification immédiate, sans bloquer l'UI.
                        screenModelScope.launch {
                            runCatching { savedWorkManager.runDue(syncChannels = true) }
                        }
                    }
                }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Configuration de la veille impossible" }
                    _state.update { it.copy(message = "Veille impossible") }
                }
        }
    }

    fun resolveWork(
        workId: String,
        query: String?,
        provider: TelegramMetadataProvider = TelegramMetadataProvider.ANILIST,
    ) {
        val work = savedStore.find(workId) ?: return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { savedWorkManager.resolve(work, query, provider) }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Résolution ${provider.label} impossible" }
                }
            val resolved = result.getOrNull()
            _state.update {
                it.copy(
                    busy = false,
                    message = when {
                        resolved != null -> "${provider.label} : ${resolved.title}"
                        result.isFailure -> "${provider.label} indisponible"
                        else -> "${provider.label} : aucun résultat"
                    },
                )
            }
        }
    }

    private fun formatInterval(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} j"
        else -> "${minutes / 60} h"
    }

    private fun addToGroups(
        groups: MutableList<TelegramSearchGroupUi>,
        file: TelegramSearchFileUi,
    ) {
        val key = "${file.kind.name}:${
            TelegramTitleParser.signature(file.title.ifBlank { file.displayName })
        }"
        val index = groups.indexOfFirst { it.groupKey == key }

        if (index == -1) {
            groups += TelegramSearchGroupUi(
                id = UUID.randomUUID().toString(),
                groupKey = key,
                title = file.title.ifBlank { file.displayName }.toDisplayTitle(),
                kind = file.kind,
                files = listOf(file),
                indexed = file.indexedWorkKey != null,
                coverUrl = file.coverUrl,
            )
        } else {
            val group = groups[index]
            if (group.files.any { it.key == file.key }) return
            groups[index] = group.copy(
                files = (group.files + file).sortedForFiles(),
                indexed = group.indexed || file.indexedWorkKey != null,
                coverUrl = group.coverUrl ?: file.coverUrl,
            )
        }
    }

    private fun TelegramFileMetadata.toSearchFile(
        chatId: Long,
        chatTitle: String,
    ): TelegramSearchFileUi? {
        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isVideoDoc = kind == TelegramFileKind.DOCUMENT &&
            (mime.startsWith("video/") || ext in VIDEO_EXTENSIONS)
        val mediaKind = when {
            kind == TelegramFileKind.VIDEO || isVideoDoc -> TelegramMediaKind.VIDEO
            kind == TelegramFileKind.DOCUMENT -> TelegramMediaKind.DOCUMENT
            else -> return null
        }

        val parsed = TelegramTitleParser.parse(
            fileName = fileName,
            caption = caption,
            kind = if (mediaKind == TelegramMediaKind.VIDEO) {
                TelegramTitleParser.Kind.VIDEO
            } else {
                TelegramTitleParser.Kind.DOCUMENT
            },
        )

        if (TelegramFileHeuristics.isUnsupportedFile(fileName, mimeType) ||
            TelegramFileHeuristics.isSuspect(fileName, caption, parsed.title)
        ) {
            return null
        }

        if (mediaKind == TelegramMediaKind.VIDEO && sizeBytes < EpisodeMediaValidator.MIN_EPISODE_BYTES) {
            return null
        }

        return TelegramSearchFileUi(
            chatId = chatId,
            chatTitle = chatTitle,
            messageId = messageId,
            fileId = fileId,
            fileName = fileName.ifBlank { "Fichier $messageId" },
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            caption = caption,
            date = date,
            threadId = messageThreadId,
            kind = mediaKind,
            title = parsed.title,
            season = parsed.season,
            episode = parsed.episode,
            episodeEnd = parsed.episodeEnd,
            chapter = parsed.chapterStart,
            chapterEnd = parsed.chapterEnd,
            language = parsed.language,
            variant = parsed.variant,
        )
    }

    private fun searchTokens(query: String): List<String> =
        normalizeSearchText(query)
            .split(NON_ALNUM)
            .filter { it.isNotBlank() }
            .filter { it.length > 1 || it.all(Char::isDigit) }

    private fun normalizeSearchText(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)

    /** Un fichier correspond si tous les mots sont présents, dans n'importe quel ordre. */
    private fun String.toDisplayTitle(): String {
        val value = trim()
        if (value.isEmpty()) return value
        if (value != value.uppercase(Locale.ROOT)) return value
        return value.split(' ')
            .joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
    }

    private fun TelegramIndexedFile.matchesSearch(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val haystack = searchHaystack()
        return tokens.all { haystack.contains(it) }
    }

    private fun TelegramIndexedFile.searchHaystack(): String = normalizeSearchText(
        buildString {
            append(fileName).append(' ')
            caption?.let { append(it).append(' ') }
            append(title).append(' ')
            language?.let { append(it).append(' ') }
            variant?.let { append(it).append(' ') }
            season?.let {
                append("s$it s${it.toString().padStart(2, '0')} saison $it ")
            }
            episode?.let {
                val pad = it.toString().padStart(2, '0')
                append("e$it e$pad ep$it ep$pad episode $it episode $pad ")
            }
            chapterStart?.let {
                val pad = it.toString().padStart(2, '0')
                append("chapitre $it chapitre $pad ")
            }
        },
    )

    private fun TelegramIndexedFile.toSearchFile(
        chatTitle: String,
        indexedWorkKey: String? = null,
        coverUrl: String? = null,
    ): TelegramSearchFileUi {
        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isVideo = TelegramFileHeuristics.isVideoFile(fileName, mimeType) ||
            (mediaKind == TelegramMediaKind.VIDEO && mime.isBlank() && ext.isBlank())
        val effectiveKind = if (isVideo) {
            TelegramMediaKind.VIDEO
        } else {
            TelegramMediaKind.DOCUMENT
        }

        // Re-parse avec le parseur courant : les index construits par une
        // ancienne version gardent sinon des titres périmés (S03E01VF, etc.).
        val parsed = TelegramTitleParser.parse(
            fileName = fileName,
            caption = caption,
            kind = if (effectiveKind == TelegramMediaKind.VIDEO) {
                TelegramTitleParser.Kind.VIDEO
            } else {
                TelegramTitleParser.Kind.DOCUMENT
            },
        )

        return TelegramSearchFileUi(
            chatId = chatId,
            chatTitle = chatTitle,
            messageId = messageId,
            fileId = fileId,
            fileName = fileName.ifBlank { "video_$messageId" },
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            caption = caption,
            date = date,
            threadId = threadId,
            kind = effectiveKind,
            title = parsed.title.ifBlank { title },
            season = parsed.season ?: season,
            episode = parsed.episode ?: episode,
            episodeEnd = parsed.episodeEnd ?: episodeEnd,
            chapter = parsed.chapterStart ?: chapterStart,
            chapterEnd = parsed.chapterEnd ?: chapterEnd,
            language = parsed.language ?: language,
            variant = parsed.variant ?: variant,
            indexedWorkKey = indexedWorkKey,
            coverUrl = coverUrl,
        )
    }

    private fun TelegramSearchFileUi.toSavedFile(): TelegramSavedFile = TelegramSavedFile(
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
        chapter = chapter,
        chapterEnd = chapterEnd,
        language = language,
        variant = variant,
    )

    private fun List<TelegramSearchGroupUi>.sortedForDisplay(): List<TelegramSearchGroupUi> =
        sortedWith(compareBy({ it.kind.ordinal }, { -it.fileCount }, { it.title.lowercase() }))

    private fun List<TelegramSearchFileUi>.sortedForFiles(): List<TelegramSearchFileUi> =
        sortedWith(
            compareBy(
                { it.season ?: 0 },
                { it.episode ?: Int.MAX_VALUE },
                { it.chapter ?: Int.MAX_VALUE },
                { it.language ?: "" },
                { it.variant ?: "" },
            ),
        )

    // ------------------------------------------------------------------
    // Synchronisation / import
    // ------------------------------------------------------------------

    fun syncAll() {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { indexManager.syncAll() }
                .onFailure { logcat(LogPriority.WARN, it) { "Synchronisation Telegram impossible" } }

            refresh()
            _state.update {
                it.copy(
                    busy = false,
                    message = result.exceptionOrNull()?.message ?: "Synchronisation terminée",
                )
            }
        }
    }

    fun scanFullHistory(chatId: Long) {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = runCatching { indexManager.scanFullHistory(chatId) }
                .map { scanned ->
                    val complete = indexManager.channel(chatId)?.historyComplete == true
                    if (complete) {
                        "Historique complet : $scanned messages examinés"
                    } else {
                        "Historique partiel : $scanned messages examinés"
                    }
                }
                .onFailure { logcat(LogPriority.WARN, it) { "Scan complet impossible" } }
                .getOrElse { it.message ?: "Scan impossible" }

            refresh()
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun joinChannel(chatId: Long) {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = runCatching { indexManager.joinChannel(chatId) }
                .map { "Canal rejoint" }
                .onFailure { logcat(LogPriority.WARN, it) { "Rejoindre le canal impossible" } }
                .getOrElse { it.message ?: "Impossible de rejoindre le canal" }

            refresh()
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun openWork(work: TelegramWorkUi, onOpened: (Long) -> Unit) {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val animeId = runCatching {
                importer.ensureAggregatedWork(
                    workKey = work.workKey,
                    title = work.title,
                    coverUrl = work.coverUrl,
                    resolved = workOverrides.snapshot().metadataFor(work.workKey),
                )
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Création de l'œuvre Telegram impossible" } }
                .getOrNull()
            _state.update { it.copy(busy = false) }
            if (animeId != null) onOpened(animeId)
        }
    }

    fun importChannel(
        chatId: Long,
        includeUnknown: Boolean = true,
        includeDocuments: Boolean = true,
    ) {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = runCatching {
                if (indexManager.channel(chatId) == null) {
                    indexManager.syncChannel(chatId)
                }
                val result = importer.importChannel(chatId, includeUnknown, includeDocuments)
                "${result.categoryName} : ${result.imported} œuvres importées"
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Import Telegram impossible" } }
                .getOrElse { it.message ?: "Import impossible" }

            refresh()
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun importAll() {
        if (_state.value.busy) return
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            var total = 0
            val errors = mutableListOf<String>()

            preferences.allChatIds.forEach { chatId ->
                runCatching {
                    if (indexManager.channel(chatId) == null) {
                        indexManager.syncChannel(chatId)
                    }
                    total += importer.importChannel(chatId).imported
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "Import du canal $chatId impossible" }
                    errors += (it.message ?: "Import impossible")
                }
            }

            refresh()
            _state.update {
                it.copy(
                    busy = false,
                    message = errors.firstOrNull() ?: "$total œuvres importées",
                )
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Retire des œuvres les exports génériques (VID_..., YouCut_...) et leurs entrées. */
    fun cleanupSuspects() {
        screenModelScope.launch {
            val changed = runCatching { savedStore.removeSuspectFiles() }
                .onFailure { logcat(LogPriority.WARN, it) { "Nettoyage des œuvres impossible" } }
                .getOrDefault(false)
            runCatching { importer.removeGenericWorks() }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Nettoyage des entrées génériques impossible" }
                }
            refresh()
            _state.update {
                it.copy(
                    message = if (changed) {
                        "Fichiers suspects supprimés"
                    } else {
                        "Aucun fichier suspect"
                    },
                )
            }
        }
    }

    fun channelWorks(chatId: Long): List<TelegramWorkUi> {
        val channel = indexManager.channel(chatId) ?: return emptyList()
        val overrideSnapshot = workOverrides.snapshot()
        // Regrouper une seule fois évite un parcours complet des fichiers pour
        // chaque œuvre (les gros canaux comptent des dizaines de milliers de
        // fichiers, et l'écran se recalcule à chaque révision).
        val filesByWork = channel.files.groupBy { it.workId }
        return channel.works
            .filter { it.mediaKind == TelegramMediaKind.VIDEO && it.episodeCount > 0 }
            .filterNot { TelegramFileHeuristics.isGenericTitle(it.displayTitle) }
            .mapNotNull { work ->
                val baseKey = work.aggregationKey().ifBlank {
                    TelegramTitleParser.signature(work.displayTitle)
                }
                val key = overrideSnapshot.resolve(baseKey)
                if (overrideSnapshot.isHidden(key)) return@mapNotNull null
                val workFiles = filesByWork[work.id].orEmpty()
                TelegramWorkUi(
                    id = work.id,
                    chatId = work.chatId,
                    title = overrideSnapshot.titleFor(key) ?: work.displayTitle,
                    kind = "Vidéo",
                    files = work.fileCount,
                    episodes = work.episodeCount,
                    resolved = !work.isUnresolved || overrideSnapshot.isRecognized(key),
                    coverUrl = overrideSnapshot.coverFor(key) ?: work.coverUrl,
                    lastFileDate = work.lastFileDate,
                    chatIds = listOf(work.chatId),
                    workKey = key,
                    rawKey = baseKey,
                    fileKeys = workFiles.map { it.identityKey() }.distinct(),
                    episodeKeys = workFiles.map { it.episodeKey() }.distinct(),
                )
            }
            .groupBy { it.workKey }
            .map { (key, contributions) ->
                val first = contributions.first()
                val fileKeys = contributions.flatMap { it.fileKeys }.distinct()
                val episodeKeys = contributions.flatMap { it.episodeKeys }.distinct()
                first.copy(
                    title = overrideSnapshot.titleFor(key) ?: first.title,
                    files = if (fileKeys.isNotEmpty()) fileKeys.size else contributions.sumOf { it.files },
                    episodes = uniqueEpisodeCount(contributions),
                    resolved = contributions.any { it.resolved },
                    coverUrl = contributions.firstNotNullOfOrNull { it.coverUrl },
                    lastFileDate = contributions.maxOfOrNull { it.lastFileDate } ?: 0L,
                    chatIds = contributions.flatMap { it.chatIds }.distinct(),
                    fileKeys = fileKeys,
                    episodeKeys = episodeKeys,
                )
            }
            .sortedWith(compareBy({ it.kind }, { it.title.lowercase() }))
    }

    fun mergeWorks(source: TelegramWorkUi, target: TelegramWorkUi) {
        mergeWorks(target, listOf(source))
    }

    /**
     * Fusionne plusieurs œuvres vers [target]. Les fichiers identiques sont
     * dédupliqués par la source Telegram ; les fichiers différents sont
     * conservés.
     */
    fun mergeWorks(target: TelegramWorkUi, sources: List<TelegramWorkUi>) {
        val targetKey = target.workKey.ifBlank { TelegramTitleParser.signature(target.title) }
        val snapshot = workOverrides.snapshot()
        val sourceKeys = sources
            .filter { it.kind == target.kind }
            .map { it.workKey.ifBlank { TelegramTitleParser.signature(it.title) } }
            .filter { it.isNotBlank() && it != targetKey && snapshot.resolve(it) != targetKey }
            .distinct()
        if (sourceKeys.isEmpty()) {
            _state.update { it.copy(message = "Aucune nouvelle œuvre à fusionner") }
            return
        }
        workOverrides.update { current ->
            sourceKeys.fold(current.rename(targetKey, target.title)) { merged, sourceKey ->
                merged.merge(sourceKey, targetKey)
            }
        }
        val targetFileKeys = target.fileKeys.toSet()
        val sourceFileKeys = sources
            .filter {
                val sourceKey = it.workKey.ifBlank { TelegramTitleParser.signature(it.title) }
                sourceKey in sourceKeys
            }
            .flatMap { it.fileKeys }
            .toSet()
        val addedFiles = (sourceFileKeys - targetFileKeys).size
        refresh()
        _state.update {
            it.copy(
                message = "${sourceKeys.size + 1} œuvres fusionnées" +
                    when {
                        addedFiles > 0 -> " · $addedFiles fichiers ajoutés"
                        sourceFileKeys.isNotEmpty() -> " · doublons ignorés"
                        else -> ""
                    },
            )
        }
    }

    fun recognizeWork(work: TelegramWorkUi, provider: TelegramMetadataProvider) {
        if (_state.value.busy) return
        val key = work.workKey.ifBlank { TelegramTitleParser.signature(work.title) }
        if (key.isBlank()) return

        screenModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    message = "Recherche ${provider.name}…",
                )
            }
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    if (provider == TelegramMetadataProvider.ANILIST) {
                        workResolver.resolve(
                            title = work.title,
                            season = null,
                            kind = TelegramMediaKind.VIDEO,
                            acceptAny = true,
                        )
                    } else {
                        externalResolver.search(
                            provider = provider,
                            title = work.title,
                            kind = TelegramMediaKind.VIDEO,
                        )
                    }
                }.getOrNull()
            }

            if (resolved != null && (resolved.anilistId != null || resolved.providerId != null)) {
                workOverrides.update { it.withMetadata(key, resolved) }
                // La fiche bibliothèque est secondaire : un échec d'écriture ne
                // doit pas laisser l'écran bloqué en « busy » ni annuler la
                // reconnaissance déjà persistée dans les overrides.
                runCatching {
                    withContext(Dispatchers.IO) {
                        importer.updateAggregatedWorkMetadata(key, resolved)
                    }
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "Mise à jour de la fiche Telegram impossible" }
                }
                refresh()
                _state.update {
                    it.copy(
                        busy = false,
                        message = "Reconnu : ${resolved.title ?: work.title}",
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        message = "Aucun résultat ${provider.name}",
                    )
                }
            }
        }
    }

    /** Retire les fusions qui alimentent cette œuvre : les variantes réapparaissent. */
    fun unmergeWork(work: TelegramWorkUi) {
        val key = work.workKey.ifBlank { TelegramTitleParser.signature(work.title) }
        val snapshot = workOverrides.snapshot()
        val hasMerge = snapshot.sourcesFor(snapshot.resolve(key)).size > 1
        workOverrides.update { it.unmerge(key) }
        refresh()
        _state.update {
            it.copy(
                message = if (hasMerge) "Fusion séparée" else "Aucune fusion à séparer",
            )
        }
    }

    fun renameWork(work: TelegramWorkUi, title: String) {
        val key = work.workKey.ifBlank { TelegramTitleParser.signature(work.title) }
        if (title.isBlank()) return
        workOverrides.update { it.rename(key, title) }
        refresh()
        _state.update { it.copy(message = "Titre mis à jour") }
    }

    fun hideWork(work: TelegramWorkUi) {
        val key = work.workKey.ifBlank { TelegramTitleParser.signature(work.title) }
        workOverrides.update { it.hide(key) }
        refresh()
        _state.update { it.copy(message = "Œuvre masquée de la source") }
    }

    fun resetWork(work: TelegramWorkUi) {
        val key = work.workKey.ifBlank { TelegramTitleParser.signature(work.title) }
        workOverrides.update { it.reset(key) }
        refresh()
        _state.update { it.copy(message = "Corrections manuelles réinitialisées") }
    }

    fun refresh() {
        // On ne décode que les résumés : l'index complet (plusieurs dizaines de
        // Mo) n'est chargé qu'à l'ouverture d'un canal.
        val indexed = indexManager.channelSummaries().associateBy { it.chatId }
        // Les overrides sont lus avant les compteurs : une œuvre reconnue doit
        // quitter « Non reconnus » jusque sur la carte du canal, pas seulement
        // dans le détail de la collection.
        val overrideSnapshot = workOverrides.snapshot()
        val channels = preferences.knownChatIds
            .map { chatId -> buildChannel(chatId, indexed[chatId], overrideSnapshot) }
            .sortedBy { it.title.lowercase() }

        val enabled = preferences.allChatIds
        val rawWorks = indexed.values
            .filter { it.chatId in enabled }
            .flatMap { it.works }
            .filterNot { TelegramFileHeuristics.isGenericTitle(it.title) }
            .filter { it.mediaKind == TelegramMediaKind.VIDEO && it.fileCount > 0 && it.episodeCount > 0 }
            .mapNotNull { work ->
                val baseKey = work.workKey.ifBlank { normalizeSearchText(work.title).trim() }
                val key = overrideSnapshot.resolve(baseKey)
                if (overrideSnapshot.isHidden(key)) return@mapNotNull null
                TelegramWorkUi(
                    id = work.id,
                    chatId = work.chatId,
                    title = overrideSnapshot.titleFor(key) ?: work.title,
                    kind = if (work.mediaKind == TelegramMediaKind.VIDEO) "Vidéo" else "Documents",
                    files = work.fileCount,
                    episodes = work.episodeCount,
                    resolved = work.resolved || overrideSnapshot.isRecognized(key),
                    coverUrl = overrideSnapshot.coverFor(key) ?: work.coverUrl,
                    lastFileDate = work.lastFileDate,
                    chatIds = listOf(work.chatId),
                    workKey = key,
                    rawKey = baseKey,
                    fileKeys = work.fileKeys,
                    episodeKeys = work.episodeKeys,
                )
            }

        // Une même œuvre peut être répartie sur plusieurs canaux. On l'affiche
        // une seule fois et on conserve les canaux contributeurs pour le détail.
        val indexedWorks = rawWorks
            .groupBy { it.workKey.ifBlank { normalizeSearchText(it.title).trim() } }
            .map { (key, contributions) ->
                val first = contributions.first()
                val fileKeys = contributions.flatMap { it.fileKeys }.distinct()
                val episodeKeys = contributions.flatMap { it.episodeKeys }.distinct()
                first.copy(
                    title = overrideSnapshot.titleFor(key) ?: first.title,
                    files = if (fileKeys.isNotEmpty()) fileKeys.size else contributions.sumOf { it.files },
                    episodes = uniqueEpisodeCount(contributions),
                    resolved = contributions.any { it.resolved },
                    coverUrl = contributions.firstNotNullOfOrNull { it.coverUrl },
                    lastFileDate = contributions.maxOfOrNull { it.lastFileDate } ?: 0L,
                    chatIds = contributions.flatMap { it.chatIds }.distinct(),
                    fileKeys = fileKeys,
                    episodeKeys = episodeKeys,
                )
            }
            .sortedWith(compareByDescending<TelegramWorkUi> { it.lastFileDate })

        _state.update {
            it.copy(
                channels = channels,
                enabledChatIds = enabled,
                works = indexedWorks.filter { it.resolved },
                unresolvedWorks = indexedWorks.filterNot { it.resolved },
                omdbApiKey = preferences.omdbApiKey,
                videoChats = preferences.videoChats,
                docChats = preferences.docChats,
                // Les résumés ne contiennent pas la liste détaillée : cette
                // révision force le recalcul de channelWorks() (index complet)
                // après une reconnaissance ou une fusion.
                revision = it.revision + 1,
            )
        }
    }

    /**
     * Additionne les épisodes par famille d'œuvre. Deux variantes d'une même
     * œuvre (même préfixe de clé) partagent leurs numéros ; deux œuvres
     * différentes gardent chacun leurs épisodes, même s'ils commencent à 1.
     */
    private fun uniqueEpisodeCount(contributions: List<TelegramWorkUi>): Int {
        if (contributions.isEmpty()) return 0
        val rawKeys = contributions.map { it.rawKey.ifBlank { it.workKey } }
        val baseKeys = rawKeys.map { it.substringBeforeLast(':', it) }.distinct()
        val families = contributions.groupBy { work ->
            val raw = work.rawKey.ifBlank { work.workKey }
            val base = raw.substringBeforeLast(':', raw)
            baseKeys
                .filter { base == it || base.startsWith("$it-") }
                .minByOrNull { it.length }
                ?: base
        }
        return families.values.sumOf { group ->
            val keys = group.flatMap { it.episodeKeys }.distinct()
            if (keys.isNotEmpty()) keys.size else group.maxOf { it.episodes }
        }
    }

    private fun buildChannel(
        chatId: Long,
        summary: TelegramChannelSummary?,
        overrides: TelegramWorkOverrides,
    ): TelegramChannelUi {
        val counts = summary.countsWith(overrides)
        return TelegramChannelUi(
            chatId = chatId,
            title = summary?.title ?: preferences.channelTitle(chatId) ?: "Canal $chatId",
            videoWorks = counts.videoWorks,
            docWorks = counts.docWorks,
            videoFiles = summary?.videoFiles ?: 0,
            docFiles = summary?.docFiles ?: 0,
            unknownVideo = counts.unknownVideo,
            unknownDoc = counts.unknownDoc,
            lastSyncAt = summary?.lastSyncAt ?: 0L,
            forcedWork = preferences.forcedWork(chatId),
            coverUrl = counts.works.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
            bannerUrl = counts.works.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
            previewCovers = counts.works
                .mapNotNull { it.coverUrl?.takeIf(String::isNotBlank) }
                .distinct()
                .take(3),
            messagesScanned = summary?.messagesScanned ?: 0,
            historyComplete = summary?.historyComplete == true,
            isMember = summary?.isMember,
            isForum = summary?.isForum == true,
        )
    }
}

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts")
private const val SEARCH_PAGE_SIZE = 200
private const val MAX_SEARCH_LIMIT = 2000
private const val CHANNEL_SYNC_COOLDOWN_MS = 5 * 60 * 1000L
