package eu.kanade.tachiyomi.ui.browse.catalog

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class CatalogWorkState(
    val sheetEpisode: Int? = null,
    val sourcesBusy: Boolean = false,
    val matches: List<CatalogSourceMatch> = emptyList(),
    val sourcesError: String? = null,
    val opening: Boolean = false,
    val openAnimeId: Long? = null,
    val openFromSource: Boolean = false,
)

class CatalogWorkScreenModel(
    private val work: CatalogWork,
) : ScreenModel {
    private val resolver = CatalogSourceResolver()
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get()

    private val _state = MutableStateFlow(CatalogWorkState())
    val state: StateFlow<CatalogWorkState> = _state.asStateFlow()

    private val cache = mutableMapOf<Int, List<CatalogSourceMatch>>()
    private var lastEpisode: Int? = null
    private var sourcesJob: Job? = null
    private var openJob: Job? = null

    fun findSources(episode: Int) {
        if (_state.value.sourcesBusy) return

        openJob?.cancel()
        openJob = null
        lastEpisode = episode
        val cached = cache[episode]
        if (cached != null) {
            _state.update {
                it.copy(
                    sheetEpisode = episode,
                    sourcesBusy = false,
                    matches = cached,
                    sourcesError = null,
                    opening = false,
                    openAnimeId = null,
                )
            }
            return
        }

        _state.update {
            it.copy(
                sheetEpisode = episode,
                sourcesBusy = true,
                matches = emptyList(),
                sourcesError = null,
                opening = false,
                openAnimeId = null,
            )
        }

        sourcesJob = screenModelScope.launch {
            val result = runCatching { resolver.find(work, episode) }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Résolution de l'épisode $episode impossible pour ${work.title}"
                    }
                }

            val matches = result.getOrDefault(emptyList())
            if (result.isSuccess) cache[episode] = matches

            _state.update { current ->
                if (current.sheetEpisode != episode) {
                    current
                } else {
                    current.copy(
                        sourcesBusy = false,
                        matches = matches,
                        sourcesError = if (result.isFailure) {
                            "Recherche impossible dans les sources. Réessayez."
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    fun open(match: CatalogSourceMatch) {
        if (_state.value.opening) return

        val libraryId = match.animeId
        if (match.isTelegram) {
            if (libraryId != null) {
                _state.update {
                    it.copy(
                        openAnimeId = libraryId,
                        openFromSource = false,
                        sourcesError = null,
                    )
                }
            }
            return
        }

        val anime = match.sourceAnime ?: return
        val sourceId = match.sourceId ?: return
        _state.update { it.copy(opening = true, sourcesError = null) }

        openJob = screenModelScope.launch {
            val local = runCatching {
                networkToLocalAnime.await(anime.toDomainAnime(sourceId))
            }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Impossible d'ouvrir ${match.sourceName} pour ${work.title}"
                    }
                }
                .getOrNull()

            if (local == null) {
                _state.update {
                    it.copy(
                        opening = false,
                        sourcesError = "Impossible d'ouvrir cette source. Réessayez.",
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        opening = false,
                        openAnimeId = local.id,
                        openFromSource = true,
                    )
                }
            }
        }
    }

    fun consumeOpen() {
        _state.update {
            it.copy(
                openAnimeId = null,
                openFromSource = false,
            )
        }
    }

    fun retry() {
        val episode = lastEpisode ?: return
        cache.remove(episode)
        findSources(episode)
    }

    fun closeSheet() {
        sourcesJob?.cancel()
        sourcesJob = null
        openJob?.cancel()
        openJob = null
        _state.update {
            it.copy(
                sheetEpisode = null,
                sourcesBusy = false,
                matches = emptyList(),
                sourcesError = null,
                opening = false,
            )
        }
    }
}
