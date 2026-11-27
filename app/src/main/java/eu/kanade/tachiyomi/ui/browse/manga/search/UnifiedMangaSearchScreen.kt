package eu.kanade.tachiyomi.ui.browse.manga.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.manga.UnifiedMangaSearchContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen

class UnifiedMangaSearchScreen(
    private val initialQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UnifiedMangaSearchScreenModel(initialQuery) }
        val state by screenModel.state.collectAsState()
        val apiState by screenModel.apiState.collectAsState()

        LaunchedEffect(apiState.openMangaId) {
            val mangaId = apiState.openMangaId ?: return@LaunchedEffect
            screenModel.consumeOpen()
            navigator.push(MangaScreen(mangaId, true))
        }

        UnifiedMangaSearchContent(
            state = state,
            apiState = apiState,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { query ->
                screenModel.updateSearchQuery(query)
                screenModel.searchAll()
            },
            getManga = { screenModel.getManga(it) },
            onClickSource = { source ->
                navigator.push(BrowseMangaSourceScreen(source.id, state.searchQuery))
            },
            onClickItem = { navigator.push(MangaScreen(it.id, true)) },
            onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
            onClickApiWork = screenModel::openWork,
        )
    }
}
