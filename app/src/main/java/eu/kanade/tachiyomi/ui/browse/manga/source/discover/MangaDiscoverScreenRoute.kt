package eu.kanade.tachiyomi.ui.browse.manga.source.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.manga.MangaDiscoverScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import tachiyomi.source.local.entries.manga.LocalMangaSource

class MangaDiscoverScreenRoute : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MangaDiscoverScreenModel() }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(Unit) { screenModel.load() }

        MangaDiscoverScreen(
            state = state,
            contentPadding = PaddingValues(0.dp),
            onRetry = screenModel::retry,
            onOpenTelegramSource = { /* Phase B */ },
            onOpenLocalMangaSource = {
                navigator.push(BrowseMangaSourceScreen(LocalMangaSource.ID, listingQuery = null))
            },
            onOpenWork = { /* Phase B */ },
        )
    }
}
