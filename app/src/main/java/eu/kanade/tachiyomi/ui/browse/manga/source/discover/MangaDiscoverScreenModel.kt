package eu.kanade.tachiyomi.ui.browse.manga.source.discover

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.multisource.MultiSourceMangaWorkOpener
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogRepository
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogType
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.browse.catalog.DiscoverFeed
import eu.kanade.tachiyomi.ui.browse.catalog.DiscoverItem
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
import tachiyomi.domain.entries.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

data class MangaDiscoverState(
    val loading: Boolean = true,
    val carousel: List<DiscoverItem> = emptyList(),
    val recommendations: List<CatalogWork> = emptyList(),
    val recommendationsLoading: Boolean = false,
    val error: String? = null,
    val opening: Boolean = false,
    val openMangaId: Long? = null,
)

class MangaDiscoverScreenModel : ScreenModel {
    private val repository = CatalogRepository(Injekt.get(), CatalogType.MANGA)
    private val mangaRepository: MangaRepository = Injekt.get()
    private val opener: MultiSourceMangaWorkOpener = Injekt.get()


    private val _state = MutableStateFlow(
        repository.peekDiscover()?.let { feed ->
            MangaDiscoverState(
                loading = false,
                carousel = buildCarousel(feed),
            )
        } ?: MangaDiscoverState(),
    )
    val state: StateFlow<MangaDiscoverState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedAt: Long = 0L

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (!force && loadedAt > 0 && System.currentTimeMillis() - loadedAt < CACHE_DURATION_MS) {
            _state.update { it.copy(loading = false, recommendationsLoading = false) }
            return
        }

        _state.update { it.copy(loading = true, recommendationsLoading = false, error = null) }
        loadJob = screenModelScope.launch {
            val result = runCatching {
                val library = withContext(Dispatchers.IO) {
                    mangaRepository.getLibraryManga()
                }
                val seedTitle = library
                    .maxByOrNull { it.lastRead }
                    ?.manga
                    ?.title
                    ?.takeIf { it.isNotBlank() }

                val feed = repository.discover(force)
                if (feed.isEmpty) {
                    error("Découverte indisponible")
                }

                Triple(library, seedTitle, feed)
            }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Chargement de la découverte impossible" }
                }

            val (library, seedTitle, feed) = result.getOrNull() ?: run {
                _state.update {
                    it.copy(
                        loading = false,
                        recommendationsLoading = false,
                        error = "Impossible de charger la découverte. Vérifiez la connexion.",
                    )
                }
                return@launch
            }

            val carousel = buildCarousel(feed)

            // Le carrousel est affiché dès qu'AniList a répondu. Les
            // recommandations, plus lentes, arrivent ensuite sans bloquer la page.
            _state.update {
                it.copy(
                    loading = false,
                    carousel = carousel,
                    error = null,
                    recommendationsLoading = true,
                )
            }

            val recommended = runCatching {
                seedTitle
                    ?.let { repository.recommendations(it, force) }
                    .orEmpty()
            }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Chargement des recommandations impossible" }
                }
                .getOrDefault(emptyList())

            val libraryTitles = library.map { normalize(it.manga.title) }.toSet()
            val recommendations = (recommended + feed.topRated + feed.popular)
                .filterNot { normalize(it.title) in libraryTitles }
                .distinctBy { normalize(it.title) + ":" + (it.year ?: 0) }
                .take(MAX_RECOMMENDATIONS)

            loadedAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    recommendations = recommendations,
                    recommendationsLoading = false,
                )
            }
        }
    }

    fun retry() {
        loadedAt = 0L
        load(force = true)
    }

    fun open(work: CatalogWork) {
        if (_state.value.opening) return
        _state.update { it.copy(opening = true) }

        screenModelScope.launch {
            val mangaId = runCatching { opener.open(work) }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Ouverture de l'œuvre manga multi-source impossible : ${work.title}"
                    }
                }
                .getOrNull()

            _state.update {
                it.copy(
                    opening = false,
                    openMangaId = mangaId,
                )
            }
        }
    }

    fun consumeOpen() {
        _state.update { it.copy(openMangaId = null) }
    }

    private fun buildCarousel(feed: DiscoverFeed): List<DiscoverItem> {
        return buildList {
            feed.trending.take(4).forEach { add(DiscoverItem(it, "Tendance")) }
            feed.popular.take(2).forEach { add(DiscoverItem(it, "Populaire")) }
            feed.topRated.take(2).forEach { add(DiscoverItem(it, "Mieux noté")) }
        }
            .distinctBy { normalize(it.work.title) + ":" + (it.work.year ?: 0) }
    }

    private fun normalize(value: String): String {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()
    }

    private companion object {
        const val CACHE_DURATION_MS = 60 * 60 * 1000L
        const val MAX_RECOMMENDATIONS = 4
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ALNUM = Regex("[^a-z0-9]+")
    }
}
