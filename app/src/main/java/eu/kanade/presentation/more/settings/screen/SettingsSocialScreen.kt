package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.ui.social.SocialFriendsScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Dedicated Matrix account screen. The player overlay no longer embeds a login
 * form; it only redirects here when no session is available.
 */
object SettingsSocialScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_social

    @Composable
    override fun Content() {
        val session = remember { Injekt.get<MatrixSessionManager>() }
        LaunchedEffect(Unit) {
            if (session.state.value is SocialSessionState.Unauthenticated) {
                session.restore()
            }
        }

        PreferenceScaffold(
            titleRes = getTitleRes(),
            onBackPressed = LocalBackPress.current,
            actions = { AppBarAction() },
            itemsProvider = { getPreferences() },
        )
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow
        val session = remember { Injekt.get<MatrixSessionManager>() }
        val sessionState by session.state.collectAsState()
        val scope = rememberCoroutineScope()
        var dialog by remember { mutableStateOf<SocialDialog?>(null) }
        var loginError by remember { mutableStateOf<SocialError?>(null) }

        when (dialog) {
            SocialDialog.Login -> MatrixLoginDialog(
                isBusy = sessionState is SocialSessionState.Authenticating,
                error = loginError,
                onDismiss = {
                    dialog = null
                    loginError = null
                },
                onLogin = { homeserver, userId, password ->
                    loginError = null
                    scope.launch {
                        when (val result = session.login(homeserver, userId, password)) {
                            is SocialResult.Failure -> loginError = result.error
                            is SocialResult.Success -> dialog = null
                        }
                    }
                },
            )
            SocialDialog.Logout -> MatrixLogoutDialog(
                onDismiss = { dialog = null },
                onLogout = {
                    dialog = null
                    scope.launch { session.logout() }
                },
            )
            null -> Unit
        }

        val account = (sessionState as? SocialSessionState.Authenticated)?.account
        val status = when (val state = sessionState) {
            SocialSessionState.Unauthenticated -> stringResource(MR.strings.social_matrix_disconnected)
            is SocialSessionState.Authenticating -> stringResource(MR.strings.social_matrix_connecting)
            is SocialSessionState.Authenticated -> stringResource(
                MR.strings.social_matrix_connected,
                state.account.userId,
            )
            is SocialSessionState.Error -> stringResource(
                MR.strings.social_matrix_error,
                state.error.toString(),
            )
        }

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.social_matrix_account),
                subtitle = status,
                icon = Icons.Outlined.PeopleAlt,
                enabled = sessionState !is SocialSessionState.Authenticating,
                onClick = {
                    if (account == null) {
                        loginError = null
                        dialog = SocialDialog.Login
                    } else {
                        dialog = SocialDialog.Logout
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Amis",
                subtitle = "Liste, lien de profil et QR d’ajout",
                icon = Icons.Outlined.PeopleAlt,
                enabled = account != null,
                onClick = { navigator.push(SocialFriendsScreen()) },
            ),
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.social_matrix_info),
            ),
        )
    }
}

private sealed interface SocialDialog {
    data object Login : SocialDialog
    data object Logout : SocialDialog
}

@Composable
private fun MatrixLoginDialog(
    isBusy: Boolean,
    error: SocialError?,
    onDismiss: () -> Unit,
    onLogin: (homeserverUrl: String, userId: String, password: String) -> Unit,
) {
    var homeserver by remember { mutableStateOf("https://matrix.org") }
    var userId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = !isBusy &&
        homeserver.isNotBlank() &&
        userId.isNotBlank() &&
        password.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.social_matrix_login_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = homeserver,
                    onValueChange = { homeserver = it },
                    label = { Text(stringResource(MR.strings.social_matrix_homeserver)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text(stringResource(MR.strings.social_matrix_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.social_matrix_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
                if (error != null) {
                    Text(
                        text = stringResource(MR.strings.social_matrix_error, error.toString()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    onLogin(homeserver.trim(), userId.trim(), password)
                },
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(MR.strings.social_matrix_login))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun MatrixLogoutDialog(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.social_matrix_logout_title)) },
        text = { Text(stringResource(MR.strings.social_matrix_logout_message)) },
        confirmButton = {
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(MR.strings.social_matrix_logout))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
