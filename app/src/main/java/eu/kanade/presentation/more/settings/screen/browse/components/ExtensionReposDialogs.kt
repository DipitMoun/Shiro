package eu.kanade.presentation.more.settings.screen.browse.components
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.delay
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun ExtensionRepoCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
    repoUrls: ImmutableSet<String>,
    onTelegramSubmit: (String) -> Unit = {},
) {
    var isTelegramMode by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(inputValue) { !isTelegramMode && repoUrls.contains(inputValue) }

    val isValid = if (isTelegramMode) {
        inputValue.startsWith("+") && inputValue.length >= 10
    } else {
        inputValue.isNotEmpty() && !nameAlreadyExists
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    if (isTelegramMode) {
                        onTelegramSubmit(inputValue)
                    } else {
                        onCreate(inputValue)
                    }
                    onDismissRequest()
                },
            ) {
                Text(text = if (isTelegramMode) "Continuer" else stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_add_repo))
        },
        text = {
            Column {
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

                if (isTelegramMode) {
                    Text("Connectez votre compte Telegram pour ajouter un dépôt privé.")
                } else {
                    Text(text = stringResource(AYMR.strings.action_add_repo_message))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    modifier = Modifier.focusRequester(focusRequester),
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = {
                        if (isTelegramMode) {
                            Text("Numéro de téléphone")
                        } else {
                            Text(text = stringResource(MR.strings.label_add_repo_input))
                        }
                    },
                    supportingText = {
                        if (isTelegramMode) {
                            Text("Format international: +237...")
                        } else {
                            val msgRes = if (inputValue.isNotEmpty() && nameAlreadyExists) {
                                MR.strings.error_repo_exists
                            } else {
                                MR.strings.information_required_plain
                            }
                            Text(text = stringResource(msgRes))
                        }
                    },
                    isError = !isTelegramMode && inputValue.isNotEmpty() && nameAlreadyExists,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isTelegramMode) KeyboardType.Phone else KeyboardType.Uri,
                    ),
                    singleLine = true,
                )
            }
        },
    )

    LaunchedEffect(focusRequester, isTelegramMode) {
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}
@Composable
fun ExtensionRepoDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    repo: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
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
        title = {
            Text(text = stringResource(MR.strings.action_delete_repo))
        },
        text = {
            Text(text = stringResource(MR.strings.delete_repo_confirmation, repo))
        },
    )
}

@Composable
fun ExtensionRepoConflictDialog(
    oldRepo: ExtensionRepo,
    newRepo: ExtensionRepo,
    onDismissRequest: () -> Unit,
    onMigrate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onMigrate()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_replace_repo))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_replace_repo_title))
        },
        text = {
            Text(text = stringResource(MR.strings.action_replace_repo_message, newRepo.name, oldRepo.name))
        },
    )
}

@Composable
fun ExtensionRepoConfirmDialog(
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    repo: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.action_add_repo))
        },
        text = {
            Text(text = stringResource(MR.strings.add_repo_confirmation, repo))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
