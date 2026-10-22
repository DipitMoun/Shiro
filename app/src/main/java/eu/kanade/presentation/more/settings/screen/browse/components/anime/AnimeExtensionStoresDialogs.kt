package eu.kanade.presentation.more.settings.screen.browse.components.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.telegram.TelegramChatSelectorDialog
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.ui.telegram.TelegramViewModel
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun AnimeExtensionStoreCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
    storeIndexUrls: Set<String>,
    processing: Boolean,
    errorMessage: String?,
    viewModel: TelegramViewModel? = null,
) {
    var isTelegramMode by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf("") }
    var showChatSelector by remember { mutableStateOf(false) }

    val storeAlreadyExists = remember(inputValue) { !isTelegramMode && storeIndexUrls.contains(inputValue) }

    val focusRequester = remember { FocusRequester() }

    val authState = viewModel?.authState?.collectAsState()?.value ?: TelegramAuthState.Starting
    val chats = viewModel?.chats?.collectAsState()?.value ?: emptyList()
    val isLoadingChats = viewModel?.isLoadingChats?.collectAsState()?.value ?: false

    LaunchedEffect(authState) {
        if (isTelegramMode && authState is TelegramAuthState.Ready && !showChatSelector) {
            viewModel?.loadChats()
        }
    }

    if (showChatSelector) {
        TelegramChatSelectorDialog(
            chats = chats,
            onDismissRequest = {
                showChatSelector = false
                onDismissRequest()
            },
            onSave = { selectedIds ->
                viewModel?.saveVideoChats(selectedIds)
                viewModel?.saveDocChats(selectedIds)
                showChatSelector = false
                onDismissRequest()
            },
            title = "Sélectionner les groupes à scraper",
        )
        return
    }

    val isValid = if (isTelegramMode) {
        when (authState) {
            is TelegramAuthState.WaitingPhoneNumber -> inputValue.startsWith("+") && inputValue.length >= 10
            is TelegramAuthState.WaitingCode -> inputValue.length >= 4
            else -> false
        }
    } else {
        inputValue.isNotEmpty() && !storeAlreadyExists
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title))
        },
        text = {
            Column {
                if (!isTelegramMode || authState is TelegramAuthState.WaitingPhoneNumber) {
                    TabRow(
                        selectedTabIndex = if (isTelegramMode) 1 else 0,
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        Tab(
                            selected = !isTelegramMode,
                            onClick = {
                                isTelegramMode = false
                                inputValue = ""
                            },
                            text = { Text("URL") },
                            icon = { Icon(Icons.Default.Link, contentDescription = null) },
                        )
                        Tab(
                            selected = isTelegramMode,
                            onClick = {
                                isTelegramMode = true
                                inputValue = "+"
                            },
                            text = { Text("Telegram") },
                            icon = { Icon(Icons.Default.Send, contentDescription = null) },
                        )
                    }
                }

                if (isTelegramMode) {
                    when (authState) {
                        is TelegramAuthState.WaitingPhoneNumber -> Text("Entrez votre numéro de téléphone Telegram")
                        is TelegramAuthState.WaitingCode -> Text("Entrez le code reçu par Telegram")
                        is TelegramAuthState.Ready -> {
                            Text("Connecté ! Chargement des groupes...")
                            if (!isLoadingChats && chats.isNotEmpty()) {
                                LaunchedEffect(Unit) { showChatSelector = true }
                            }
                        }
                        is TelegramAuthState.Error -> Text(
                            text = "Erreur: ${authState.message}",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                        else -> Text("Communication avec Telegram...")
                    }
                } else {
                    Text(
                        text = errorMessage ?: if (storeAlreadyExists) {
                            stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists)
                        } else {
                            stringResource(MR.strings.information_required_plain)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (
                    isTelegramMode &&
                    (authState is TelegramAuthState.WaitingPhoneNumber || authState is TelegramAuthState.WaitingCode)
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = {
                            if (authState is TelegramAuthState.WaitingPhoneNumber) {
                                Text("Numéro de téléphone")
                            } else {
                                Text("Code de confirmation")
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (authState is TelegramAuthState.WaitingPhoneNumber) {
                                KeyboardType.Phone
                            } else {
                                KeyboardType.NumberPassword
                            },
                        ),
                        singleLine = true,
                    )
                } else if (!isTelegramMode) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = {
                            Text(
                                text = stringResource(MR.strings.extensionStoresScreen_addStoreInput_inputLabel),
                            )
                        },
                        isError = errorMessage != null || storeAlreadyExists,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                }

                if (isTelegramMode && (authState is TelegramAuthState.Starting || isLoadingChats)) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isTelegramMode) {
                        when (authState) {
                            is TelegramAuthState.WaitingPhoneNumber -> viewModel?.sendPhoneNumber(inputValue)
                            is TelegramAuthState.WaitingCode -> viewModel?.sendAuthCode(inputValue)
                            else -> {}
                        }
                        inputValue = ""
                    } else {
                        onCreate(inputValue)
                    }
                },
                enabled = isValid && !processing,
            ) {
                Text(
                    text = if (isTelegramMode) {
                        "Continuer"
                    } else {
                        stringResource(
                            resource = if (processing) {
                                MR.strings.extensionStoresScreen_addStore_processing
                            } else {
                                MR.strings.action_add
                            },
                        )
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )

    LaunchedEffect(focusRequester, isTelegramMode) {
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}

@Composable
fun AnimeExtensionStoreDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    storeName: String,
    storeIndexUrl: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_title))
        },
        text = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_body, storeName, storeIndexUrl))
        },
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun AnimeExtensionStoreConfirmDialog(
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    storeIndexUrl: String,
    storeAlreadyExists: Boolean,
    processing: Boolean,
    errorMessage: String?,
) {
    val state = rememberTextFieldState(initialText = storeIndexUrl)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(MR.strings.extensionStoresScreen_addStoreDeeplink_bodyText))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = state,
                    readOnly = true,
                    supportingText = when {
                        storeAlreadyExists -> {
                            {
                                Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists))
                            }
                        }
                        errorMessage != null -> {
                            {
                                Text(text = errorMessage)
                            }
                        }
                        else -> null
                    },
                    isError = errorMessage != null || storeAlreadyExists,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = !storeAlreadyExists && !processing) {
                Text(
                    text = stringResource(
                        resource = if (processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
