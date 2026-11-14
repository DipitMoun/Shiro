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
import eu.kanade.tachiyomi.data.telegram.index.TelegramMediaKind
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramSourceScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import tachiyomi.source.local.entries.manga.LocalMangaSource

class MangaDiscoverScreenRoute : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MangaDiscoverScreenModel() }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(Unit) { screenModel.load() }

        LaunchedEffect(state.openMangaId) {
            val mangaId = state.openMangaId ?: return@LaunchedEffect
            screenModel.consumeOpen()
            navigator.push(MangaScreen(mangaId, fromSource = true))
        }

        MangaDiscoverScreen(
            state = state,
            contentPadding = PaddingValues(0.dp),
            onRetry = screenModel::retry,
            onOpenTelegramSource = {
                navigator.push(TelegramSourceScreen(TelegramMediaKind.DOCUMENT))
            },
            onOpenLocalMangaSource = {
                navigator.push(BrowseMangaSourceScreen(LocalMangaSource.ID, listingQuery = null))
            },
            onOpenWork = screenModel::open,
        )
    }
}
