package eu.kanade.tachiyomi.ui.browse.telegram

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.telegram.TelegramScreen
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.telegramTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val model = rememberScreenModel { TelegramScreenModel() }
    val state by model.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.telegram,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.telegram_sync),
                icon = Icons.Outlined.Refresh,
                onClick = { model.syncAll() },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.telegram_add_channel),
                icon = Icons.Outlined.Add,
                onClick = { navigator.push(TelegramSelectChannelsScreen(model)) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.telegram_import_tracked),
                onClick = { model.importAll() },
            ),
            AppBar.OverflowAction(
                title = "Nettoyer les fichiers suspects",
                onClick = { model.cleanupSuspects() },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.telegram_logout),
                onClick = { model.logout() },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            LaunchedEffect(state.message) {
                val message = state.message ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(message)
                model.consumeMessage()
            }

            TelegramScreen(
                state = state,
                contentPadding = contentPadding,
                onConnect = { navigator.push(TelegramSelectChannelsScreen(model)) },
                onClickChannel = { navigator.push(TelegramChannelScreen(model, it)) },
                onSearchQueryChange = model::setSearchQuery,
                onSearch = model::search,
                onContinueSearch = model::continueSearch,
                onClearSearch = model::clearSearch,
                onMergeGroups = model::mergeGroups,
                onSplitFiles = model::splitFiles,
                onRenameGroup = model::renameGroup,
                onRemoveFile = model::removeFile,
                onCreateWork = model::createWork,
                onClickWork = { work ->
                    val libraryId = work.libraryId
                    if (libraryId != null) {
                        if (work.kind == TelegramSavedKind.VIDEO) {
                            navigator.push(AnimeScreen(libraryId))
                        } else {
                            navigator.push(MangaScreen(libraryId))
                        }
                    }
                },
                onRenameWork = model::renameWork,
                onResyncWork = model::resyncWork,
                onDeleteWork = model::deleteWork,
                onSetWatch = model::setWatch,
                onResolveWork = model::resolveWork,
            )
        },
    )
}
