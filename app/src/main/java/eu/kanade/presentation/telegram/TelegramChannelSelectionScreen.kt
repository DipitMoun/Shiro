package eu.kanade.presentation.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramChatItem
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramUiState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun TelegramChannelSelectionScreen(
    state: TelegramUiState,
    navigateUp: () -> Unit,
    onConfirm: (videoChats: Set<Long>, docChats: Set<Long>) -> Unit,
    onSendPhone: (String) -> Unit,
    onSendCode: (String) -> Unit,
    onCheckPassword: (String) -> Unit,
    onResendCode: () -> Unit,
    onRetry: () -> Unit,
    onLoadChats: () -> Unit,
) {
    var videoChats by remember(state.videoChats) { mutableStateOf(state.videoChats) }
    var docChats by remember(state.docChats) { mutableStateOf(state.docChats) }

    val isReady = state.authState is TelegramAuthState.Ready

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                navigateUp = navigateUp,
                title = stringResource(MR.strings.telegram_select_channels),
                scrollBehavior = scrollBehavior,
                actions = {
                    if (isReady) {
                        TextButton(
                            onClick = { onConfirm(videoChats, docChats) },
                            enabled = !state.busy,
                        ) {
                            Text(
                                text = stringResource(MR.strings.action_ok),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        if (!isReady) {
            Box(modifier = Modifier.padding(paddingValues)) {
                TelegramAuthContent(
                    authState = state.authState,
                    loadingChats = state.loadingChats,
                    chats = state.chats,
                    onSendPhone = onSendPhone,
                    onSendCode = onSendCode,
                    onCheckPassword = onCheckPassword,
                    onResendCode = onResendCode,
                    onRetry = onRetry,
                    onLoadChats = onLoadChats,
                )
            }
            return@Scaffold
        }

        if (state.chats.isEmpty()) {
            EmptyScreen(
                MR.strings.telegram_no_chats,
                modifier = Modifier.padding(paddingValues),
            )
            return@Scaffold
        }

        TelegramChatList(
            chats = state.chats,
            videoChats = videoChats,
            docChats = docChats,
            contentPadding = paddingValues,
            onToggleVideo = { id -> videoChats = videoChats.toggle(id) },
            onToggleDoc = { id -> docChats = docChats.toggle(id) },
        )
    }
}

@Composable
private fun TelegramChatList(
    chats: List<TelegramChatItem>,
    videoChats: Set<Long>,
    docChats: Set<Long>,
    contentPadding: PaddingValues,
    onToggleVideo: (Long) -> Unit,
    onToggleDoc: (Long) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding) {
        item {
            Text(
                text = stringResource(MR.strings.telegram_select_channels),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        itemsIndexed(chats, key = { index, chat -> "chat-$index-${chat.id}" }) { _, chat ->
            ListItem(
                headlineContent = { Text(text = chat.title, maxLines = 1) },
                supportingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = chat.id in videoChats,
                            onClick = { onToggleVideo(chat.id) },
                            label = { Text(stringResource(MR.strings.telegram_channel_video)) },
                        )
                        FilterChip(
                            selected = chat.id in docChats,
                            onClick = { onToggleDoc(chat.id) },
                            label = { Text(stringResource(MR.strings.telegram_channel_document)) },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id
