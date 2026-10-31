package eu.kanade.tachiyomi.ui.browse.catalog

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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

data class CatalogSearchState(
    val query: String = "",
    val busy: Boolean = false,
    val results: List<CatalogWork> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
)

class CatalogSearchScreenModel : ScreenModel {
    private val repository = CatalogRepository(Injekt.get())

    private val _state = MutableStateFlow(CatalogSearchState())
    val state: StateFlow<CatalogSearchState> = _state.asStateFlow()

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty() || _state.value.busy) return

        screenModelScope.launch {
            _state.update {
                it.copy(busy = true, results = emptyList(), searched = false, error = null)
            }

            val result = runCatching {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    repository.search(query) { partial ->
                        if (partial.isNotEmpty()) {
                            _state.update {
                                it.copy(
                                    busy = true,
                                    searched = true,
                                    results = partial,
                                    error = null,
                                )
                            }
                        }
                    }
                }
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Recherche catalogue impossible" } }

            val currentResults = _state.value.results
            val works = result.getOrNull() ?: currentResults
            val finalResults = if (works.isNotEmpty()) works else currentResults
            _state.update {
                it.copy(
                    busy = false,
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

    fun clear() {
        _state.update { CatalogSearchState() }
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 25_000L
    }
}
