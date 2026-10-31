package eu.kanade.tachiyomi.ui.entries.anime

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.multisource.MultiSourceCompatibility
import eu.kanade.tachiyomi.data.multisource.MultiSourceEpisodeRef
import eu.kanade.tachiyomi.data.multisource.MultiSourceMappingReport
import eu.kanade.tachiyomi.data.multisource.MultiSourceMappingState
import eu.kanade.tachiyomi.data.multisource.MultiSourceMappingStore
import eu.kanade.tachiyomi.data.multisource.MultiSourceWorkStore
import eu.kanade.tachiyomi.data.multisource.PersistedSourceCompatibility
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogSourceResolver
import eu.kanade.tachiyomi.ui.browse.catalog.SourceCoverage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MultiSourceMappingScreenModel(
    private val workUrl: String,
) : ScreenModel {
    private val workStore: MultiSourceWorkStore = Injekt.get()
    private val mappingStore: MultiSourceMappingStore = Injekt.get()
    private val extensionManager: AnimeExtensionManager = Injekt.get()
    private val resolver = CatalogSourceResolver()

    private val _state = MutableStateFlow(MultiSourceMappingState())
    val state: StateFlow<MultiSourceMappingState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (!force && loaded) return

        if (!force) {
            val report = mappingStore.getReport(workUrl)
            val currentSelection = mappingStore.getSelectedSourceIds()
            val reportSelection = report?.selectedSourceIds?.toSet()
            val selectionMatches = if (currentSelection == null) {
                reportSelection == null
            } else {
                reportSelection == currentSelection
            }
            if (
                report != null &&
                report.version >= MultiSourceMappingReport.CURRENT_VERSION &&
                selectionMatches
            ) {
                mappingStore.activeWorkUrl = workUrl
                val extensions = extensionManager.installedExtensionsFlow.value
                val sources = report.sources.map { persisted ->
                    MultiSourceCompatibility(
                        sourceId = persisted.sourceId,
                        sourceName = persisted.sourceName,
                        extension = extensions.firstOrNull { extension ->
                            extension.sources.any { it.id == persisted.sourceId }
                        },
                        compatibility = persisted.compatibility,
                        coveredEpisodes = persisted.coveredEpisodes,
                        assignedEpisodes = persisted.assignedEpisodes,
                    )
                }
                loaded = true
                _state.value = MultiSourceMappingState(
                    loading = false,
                    totalEpisodes = report.totalEpisodes,
                    assignedEpisodes = report.assignedEpisodes,
                    complete = report.complete,
                    sources = sources,
                    error = null,
                )
                return
            }
        }

        _state.value = _state.value.copy(loading = true, error = null)
        mappingStore.activeWorkUrl = workUrl
        loadJob = screenModelScope.launch {
            val work = workStore.get(workUrl)
            if (work == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Œuvre multi-source introuvable.",
                )
                return@launch
            }

            val total = work.episodes ?: 0
            if (total <= 0) {
                _state.value = MultiSourceMappingState(
                    loading = false,
                    totalEpisodes = 0,
                    error = "Nombre d'épisodes inconnu.",
                )
                return@launch
            }

            val selectedSourceIds = mappingStore.getSelectedSourceIds()
            val extensions = extensionManager.installedExtensionsFlow.value
            val installedSourceIds = extensions
                .flatMap { extension -> extension.sources.map { it.id } }
                .toSet()

            fun isAllowedSourceId(sourceId: Long): Boolean {
                val selected = selectedSourceIds == null || sourceId in selectedSourceIds
                val installed = installedSourceIds.isEmpty() || sourceId in installedSourceIds
                return selected && installed
            }

            fun isAllowedSource(ref: MultiSourceEpisodeRef): Boolean =
                isAllowedSourceId(ref.sourceId)

            val previousAssignments = mappingStore.get(workUrl)
                .filterKeys { it in 1..total }
                .filterValues { isAllowedSource(it) }
            val previousAlternatives = mappingStore.getAlternatives(workUrl)
                .filterKeys { it in 1..total }
                .mapValues { (_, refs) -> refs.filter { isAllowedSource(it) } }
                .filterValues { it.isNotEmpty() }
            val previousReport = mappingStore.getReport(workUrl)

            val prioritySourceIds = buildSet {
                addAll(previousReport?.sources.orEmpty().map { it.sourceId })
                addAll(previousAssignments.values.map { it.sourceId })
                addAll(previousAlternatives.values.flatten().map { it.sourceId })
                addAll(mappingStore.getFrequentlyUsedSourceIds())
            }

            val coverages = runCatching {
                resolver.coverage(
                    work = work,
                    selectedSourceIds = selectedSourceIds,
                    prioritySourceIds = prioritySourceIds,
                )
            }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Mapping multi-source impossible pour ${work.title}" }
                }
                .getOrDefault(emptyList())
                .sortedWith(
                    compareByDescending<SourceCoverage> { coverage ->
                        coverage.episodes.keys.count { it in 1..total }
                    }.thenBy { it.sourceName.lowercase() },
                )

            val newAssignments = linkedMapOf<Int, MultiSourceEpisodeRef>()
            val newRefsByEpisode = mutableMapOf<Int, MutableList<MultiSourceEpisodeRef>>()

            coverages.forEach { coverage ->
                coverage.episodes.forEach { (number, episode) ->
                    if (number in 1..total) {
                        val ref = MultiSourceEpisodeRef(
                            sourceId = coverage.sourceId,
                            sourceName = coverage.sourceName,
                            animeUrl = coverage.sourceAnime.url,
                            animeTitle = coverage.sourceAnime.title,
                            episodeUrl = episode.url,
                            episodeNumber = number,
                            episodeName = episode.name,
                        )
                        newRefsByEpisode.getOrPut(number) { mutableListOf() }.add(ref)

                        if (number !in newAssignments) {
                            newAssignments[number] = ref
                        }
                    }
                }
            }

            // Une couverture partielle ne doit jamais effacer un mapping qui
            // fonctionnait déjà : on garde l'ancienne source principale et on
            // conserve les anciennes alternatives en secours.
            val assignments = linkedMapOf<Int, MultiSourceEpisodeRef>()
            for (number in 1..total) {
                val primary = previousAssignments[number] ?: newAssignments[number] ?: continue
                assignments[number] = primary
            }

            val alternatives = buildMap {
                for (number in 1..total) {
                    val primary = assignments[number] ?: continue
                    val candidates = buildList {
                        add(previousAssignments[number])
                        addAll(previousAlternatives[number].orEmpty())
                        addAll(newRefsByEpisode[number].orEmpty())
                    }
                    val fallbacks = candidates
                        .filterNotNull()
                        .filterNot {
                            it.sourceId == primary.sourceId && it.episodeUrl == primary.episodeUrl
                        }
                        .distinctBy { it.sourceId to it.episodeUrl }
                        .take(MAX_ALTERNATIVES)
                    if (fallbacks.isNotEmpty()) put(number, fallbacks)
                }
            }

            mappingStore.save(workUrl, assignments)
            mappingStore.saveAlternatives(workUrl, alternatives)

            val coverageById = coverages.associateBy { it.sourceId }
            val previousSourceById = previousReport
                ?.sources
                .orEmpty()
                .associateBy { it.sourceId }
            val sourceNames = buildMap {
                assignments.values.forEach { put(it.sourceId, it.sourceName) }
                alternatives.values.flatten().forEach { put(it.sourceId, it.sourceName) }
            }
            val sourceIds = buildSet {
                addAll(coverages.map { it.sourceId })
                addAll(sourceNames.keys)
                addAll(
                    previousSourceById.keys.filter { sourceId ->
                        isAllowedSourceId(sourceId) &&
                            (sourceId in sourceNames || sourceId in coverageById)
                    },
                )
            }

            val sources = sourceIds.mapNotNull { sourceId ->
                val coverage = coverageById[sourceId]
                val previous = previousSourceById[sourceId]
                val sourceName = coverage?.sourceName
                    ?: previous?.sourceName
                    ?: sourceNames[sourceId]
                    ?: return@mapNotNull null
                val covered = coverage?.episodes?.keys?.count { it in 1..total }
                    ?: previous?.coveredEpisodes
                    ?: 0
                val assigned = assignments.values.count { it.sourceId == sourceId }
                MultiSourceCompatibility(
                    sourceId = sourceId,
                    sourceName = sourceName,
                    extension = extensions.firstOrNull { extension ->
                        extension.sources.any { it.id == sourceId }
                    },
                    compatibility = if (total > 0) covered.toFloat() / total else 0f,
                    coveredEpisodes = covered,
                    assignedEpisodes = assigned,
                )
            }.sortedWith(
                compareByDescending<MultiSourceCompatibility> { it.assignedEpisodes }
                    .thenByDescending { it.coveredEpisodes }
                    .thenBy { it.sourceName.lowercase() },
            )

            val complete = assignments.size == total
            mappingStore.saveReport(
                workUrl = workUrl,
                report = MultiSourceMappingReport(
                    totalEpisodes = total,
                    assignedEpisodes = assignments.size,
                    complete = complete,
                    sources = sources.map { source ->
                        PersistedSourceCompatibility(
                            sourceId = source.sourceId,
                            sourceName = source.sourceName,
                            compatibility = source.compatibility,
                            coveredEpisodes = source.coveredEpisodes,
                            assignedEpisodes = source.assignedEpisodes,
                        )
                    },
                    version = MultiSourceMappingReport.CURRENT_VERSION,
                    selectedSourceIds = selectedSourceIds?.sorted(),
                ),
            )

            loaded = true
            _state.value = MultiSourceMappingState(
                loading = false,
                totalEpisodes = total,
                assignedEpisodes = assignments.size,
                complete = complete,
                sources = sources,
                error = if (assignments.isEmpty()) {
                    "Aucune source compatible trouvée pour cette œuvre."
                } else {
                    null
                },
            )
        }
    }

    fun retry() {
        loaded = false
        load(force = true)
    }

    private companion object {
        const val MAX_ALTERNATIVES = 3
    }
}
