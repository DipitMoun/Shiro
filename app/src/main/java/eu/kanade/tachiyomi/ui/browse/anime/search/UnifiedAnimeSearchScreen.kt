package eu.kanade.tachiyomi.ui.browse.anime.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.anime.UnifiedAnimeSearchContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen

class UnifiedAnimeSearchScreen(
    private val initialQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UnifiedAnimeSearchScreenModel(initialQuery) }
        val state by screenModel.state.collectAsState()
        val apiState by screenModel.apiState.collectAsState()

        LaunchedEffect(apiState.openAnimeId) {
            val animeId = apiState.openAnimeId ?: return@LaunchedEffect
            screenModel.consumeOpen()
            navigator.push(AnimeScreen(animeId, fromSource = true))
        }

        UnifiedAnimeSearchContent(
            state = state,
            apiState = apiState,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { query ->
                screenModel.updateSearchQuery(query)
                screenModel.searchAll()
            },
            getAnime = { screenModel.getAnime(it) },
            onClickSource = { source ->
                navigator.push(BrowseAnimeSourceScreen(source.id, state.searchQuery))
            },
            onClickItem = { navigator.push(AnimeScreen(it.id, true)) },
            onLongClickItem = { navigator.push(AnimeScreen(it.id, true)) },
            onClickApiWork = screenModel::openWork,
        )
    }
}
