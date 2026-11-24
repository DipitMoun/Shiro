package eu.kanade.tachiyomi.ui.entries.manga

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.multisource.MangaMappingState
import eu.kanade.tachiyomi.data.multisource.MangaSourceCompatibility
import eu.kanade.tachiyomi.data.multisource.MultiSourceMangaMapper
import eu.kanade.tachiyomi.data.multisource.MultiSourceMangaSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceWorkStore
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * State of the chapter mapping of a multi-source manga, shown on its page the same way the anime
 * page shows its episode mapping: a progress state while the sources are searched, then how much
 * of the work each source covers.
 */
class MultiSourceMangaMappingScreenModel(
    private val workUrl: String,
) : ScreenModel {

    private val mapper: MultiSourceMangaMapper = Injekt.get()
    private val workStore: MultiSourceWorkStore = Injekt.get()
    private val extensionManager: MangaExtensionManager = Injekt.get()

    private val _state = MutableStateFlow(MangaMappingState())
    val state: StateFlow<MangaMappingState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (!force && loaded) return

        if (!force) {
            mapper.cached(workUrl)?.let {
                publish(it)
                loaded = true
                return
            }
        }

        _state.value = _state.value.copy(loading = true, error = null)
        loadJob = screenModelScope.launch {
            val mapping = runCatching { mapper.ensure(workUrl, force) }
                .onFailure { logcat(LogPriority.WARN, it) { "Mapping manga impossible pour $workUrl" } }
                .getOrNull()
            loaded = true
            if (mapping == null) {
                _state.value = MangaMappingState(loading = false, error = "Œuvre multi-source introuvable.")
            } else {
                publish(mapping)
            }
        }
    }

    /** Searches the sources again. */
    fun retry() {
        loaded = false
        load(force = true)
    }

    /**
     * Makes [sourceId] the primary source of the work (null goes back to automatic) and rebuilds
     * the mapping; [onDone] runs once the new chapters can be fetched.
     */
    fun selectSource(sourceId: Long?, onDone: () -> Unit) {
        MultiSourceMangaSource.setPreferredSource(workUrl, sourceId)
        loaded = false
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null, preferredSourceId = sourceId)
        loadJob = screenModelScope.launch {
            val mapping = runCatching { mapper.ensure(workUrl, force = true) }.getOrNull()
            loaded = true
            if (mapping != null) publish(mapping)
            onDone()
        }
    }

    private fun publish(mapping: MultiSourceMangaMapper.Mapping) {
        val extensions = extensionManager.installedExtensionsFlow.value
        val sources = mapping.sources.map { source ->
            MangaSourceCompatibility(
                sourceId = source.sourceId,
                sourceName = source.sourceName,
                extension = extensions.firstOrNull { extension ->
                    extension.sources.any { it.id == source.sourceId }
                },
                compatibility = source.compatibility,
                coveredChapters = source.coveredEpisodes,
                assignedChapters = source.assignedEpisodes,
            )
        }.sortedWith(
            compareByDescending<MangaSourceCompatibility> { it.assignedChapters }
                .thenByDescending { it.coveredChapters }
                .thenBy { it.sourceName.lowercase() },
        )
        _state.value = MangaMappingState(
            loading = false,
            totalChapters = mapping.total,
            assignedChapters = mapping.assigned,
            complete = mapping.complete,
            sources = sources,
            preferredSourceId = workStore.preferredSource(workUrl),
            error = if (mapping.assigned == 0) "Aucune source compatible trouvée pour cette œuvre." else null,
        )
    }
}
