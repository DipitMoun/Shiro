package eu.kanade.tachiyomi.ui.browse.anime.search

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceAnimeSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceWorkOpener
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSourceFilter
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogRepository
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class ApiSearchState(
    val loading: Boolean = false,
    val results: List<CatalogWork> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
    val opening: Boolean = false,
    val openAnimeId: Long? = null,
)

class UnifiedAnimeSearchScreenModel(
    initialQuery: String = "",
) : AnimeSearchScreenModel(
    State(
        searchQuery = initialQuery,
        sourceFilter = AnimeSourceFilter.All,
    ),
) {
    private val catalogRepository = CatalogRepository(Injekt.get())
    private val opener: MultiSourceWorkOpener = Injekt.get()

    private val _apiState = MutableStateFlow(ApiSearchState())
    val apiState: StateFlow<ApiSearchState> = _apiState.asStateFlow()

    private var apiJob: Job? = null

    init {
        if (initialQuery.isNotBlank()) {
            searchAll()
        }
    }

    override fun getEnabledSources(): List<AnimeSource> {
        return super.getEnabledSources()
            .filter { it.id != MultiSourceAnimeSource.ID }
    }

    fun searchAll() {
        val query = state.value.searchQuery?.trim().orEmpty()
        if (query.isEmpty()) return

        super.search()
        searchCatalog(query)
    }

    private fun searchCatalog(query: String) {
        apiJob?.cancel()
        _apiState.value = ApiSearchState(loading = true)

        apiJob = screenModelScope.launch {
            val result = runCatching {
                withTimeoutOrNull(API_TIMEOUT_MS) {
                    catalogRepository.search(query) { partial ->
                        if (partial.isNotEmpty()) {
                            _apiState.update {
                                it.copy(
                                    loading = true,
                                    searched = true,
                                    results = partial,
                                    error = null,
                                )
                            }
                        }
                    }
                }
            }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Recherche catalogue unifiée impossible" }
                }

            val current = _apiState.value.results
            val works = result.getOrNull() ?: current
            val finalResults = if (works.isNotEmpty()) works else current

            _apiState.update {
                it.copy(
                    loading = false,
                    searched = true,
                    results = finalResults,
                    error = when {
                        result.isFailure && finalResults.isEmpty() -> "Catalogue indisponible. Réessayez."
                        result.isSuccess && result.getOrNull() == null && finalResults.isEmpty() ->
                            "La recherche a expiré. Réessayez."
                        finalResults.isEmpty() -> "Aucun résultat dans le catalogue."
                        else -> null
                    },
                )
            }
        }
    }

    fun openWork(work: CatalogWork) {
        if (_apiState.value.opening) return
        _apiState.update { it.copy(opening = true) }

        screenModelScope.launch {
            val animeId = runCatching { opener.open(work) }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Ouverture de l'œuvre multi-source impossible : ${work.title}"
                    }
                }
                .getOrNull()

            _apiState.update {
                it.copy(
                    opening = false,
                    openAnimeId = animeId,
                )
            }
        }
    }

    fun consumeOpen() {
        _apiState.update { it.copy(openAnimeId = null) }
    }

    private companion object {
        const val API_TIMEOUT_MS = 25_000L
    }
}
