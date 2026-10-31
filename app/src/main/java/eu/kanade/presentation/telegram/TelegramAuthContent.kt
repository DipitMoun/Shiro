package eu.kanade.presentation.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramChatItem
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * Formulaire de connexion Telegram (téléphone, code, mot de passe 2FA).
 */
@Composable
fun TelegramAuthContent(
    authState: TelegramAuthState,
    loadingChats: Boolean,
    chats: List<TelegramChatItem>,
    onSendPhone: (String) -> Unit,
    onSendCode: (String) -> Unit,
    onCheckPassword: (String) -> Unit,
    onResendCode: () -> Unit,
    onRetry: () -> Unit,
    onLoadChats: () -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val isInputStep = authState is TelegramAuthState.WaitingPhoneNumber ||
        authState is TelegramAuthState.WaitingCode ||
        authState is TelegramAuthState.WaitingPassword

    LaunchedEffect(authState) {
        if (isInputStep) {
            delay(150.milliseconds)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(MR.strings.telegram_connect),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(12.dp))

        when (authState) {
            is TelegramAuthState.WaitingPhoneNumber -> {
                Text(text = stringResource(MR.strings.telegram_auth_phone))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(MR.strings.telegram_phone)) },
                    placeholder = { Text("+33612345678") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                )
            }

            is TelegramAuthState.WaitingCode -> {
                Text(
                    text = if (authState.phoneNumber.isBlank()) {
                        stringResource(MR.strings.telegram_auth_code)
                    } else {
                        stringResource(MR.strings.telegram_auth_code_phone, authState.phoneNumber)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(MR.strings.telegram_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                TextButton(onClick = onResendCode) {
                    Text(text = stringResource(MR.strings.telegram_resend_code))
                }
            }

            is TelegramAuthState.WaitingPassword -> {
                Text(
                    text = if (authState.passwordHint.isBlank()) {
                        stringResource(MR.strings.telegram_auth_password)
                    } else {
                        stringResource(MR.strings.telegram_auth_password_hint, authState.passwordHint)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.telegram_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
            }

            is TelegramAuthState.Ready -> {
                Text(
                    text = stringResource(MR.strings.telegram_connected),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (loadingChats) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (chats.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.telegram_no_chats),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onLoadChats) {
                        Text(text = stringResource(MR.strings.telegram_reload))
                    }
                }
            }

            is TelegramAuthState.WaitingOtherDevice -> {
                Text(text = stringResource(MR.strings.telegram_auth_other_device))
                Spacer(modifier = Modifier.height(8.dp))
                if (authState.link.isNotBlank()) {
                    Text(
                        text = authState.link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is TelegramAuthState.WaitingRegistration -> {
                Text(text = stringResource(MR.strings.telegram_auth_registration))
            }

            is TelegramAuthState.Error -> {
                Text(
                    text = authState.message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(MR.strings.telegram_retry))
                }
            }

            TelegramAuthState.Closed -> {
                Text(text = stringResource(MR.strings.telegram_no_channels_summary))
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(MR.strings.telegram_connect))
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (authState) {
            is TelegramAuthState.WaitingPhoneNumber -> {
                Button(
                    onClick = { onSendPhone(phone.trim()) },
                    enabled = phone.startsWith("+") && phone.length >= 8,
                ) {
                    Text(text = stringResource(MR.strings.telegram_continue))
                }
            }

            is TelegramAuthState.WaitingCode -> {
                Button(
                    onClick = { onSendCode(code.trim()) },
                    enabled = code.trim().length >= 3,
                ) {
                    Text(text = stringResource(MR.strings.telegram_continue))
                }
            }

            is TelegramAuthState.WaitingPassword -> {
                Button(
                    onClick = { onCheckPassword(password) },
                    enabled = password.isNotEmpty(),
                ) {
                    Text(text = stringResource(MR.strings.telegram_continue))
                }
            }

            is TelegramAuthState.Error -> Unit

            else -> Unit
        }
    }
}
