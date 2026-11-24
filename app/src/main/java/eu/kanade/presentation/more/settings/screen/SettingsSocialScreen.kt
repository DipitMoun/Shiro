package eu.kanade.presentation.more.settings.screen

import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.powerManager
import androidx.core.net.toUri
import android.provider.Settings
import android.content.Intent
import android.content.ActivityNotFoundException
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.social.friends.SocialFriendRecord
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.activity.SocialActivityService
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialOwnProfile
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialAvatarImageProcessor
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialProfileEditor
import eu.kanade.presentation.social.FriendLibraryScreen
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
class SettingsSocialScreen : Screen() {

    @Composable
    override fun Content() {
        val session = remember { Injekt.get<MatrixSessionManager>() }
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(Unit) {
            MatrixSessionBootstrap.ensureRestored(session)
        }

        PreferenceScaffold(
            titleRes = MR.strings.pref_category_social,
            onBackPressed = navigator::pop,
            itemsProvider = { getPreferences() },
        )
    }

    @Composable
    private fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow
        val session = remember { Injekt.get<MatrixSessionManager>() }
        val sessionState by session.state.collectAsState()
        val scope = rememberCoroutineScope()
        var dialog by remember { mutableStateOf<SocialDialog?>(null) }
        var loginError by remember { mutableStateOf<SocialError?>(null) }
        val friendsStore = remember { Injekt.get<SocialFriendStore>() }
        val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
        val profileEditor = remember { Injekt.get<SocialProfileEditor>() }
        val context = LocalContext.current
        val friends by friendsStore.friends.collectAsState()
        var avatarVersion by remember { mutableStateOf(0) }
        var avatarBusy by remember { mutableStateOf(false) }
        var avatarError by remember { mutableStateOf<String?>(null) }

        val account = (sessionState as? SocialSessionState.Authenticated)?.account

        // Doze and OEM battery savers delay or kill the sync service otherwise.
        fun isIgnoringBatteryOptimizations() =
            context.powerManager.isIgnoringBatteryOptimizations(context.packageName)
        var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
        val batteryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { batteryUnrestricted = isIgnoringBatteryOptimizations() }

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

        val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null || account == null) return@rememberLauncherForActivityResult
            avatarError = null
            avatarBusy = true
            scope.launch {
                try {
                    when (val prepared = SocialAvatarImageProcessor.prepare(context, uri)) {
                        is SocialResult.Failure -> avatarError = prepared.error.toString()
                        is SocialResult.Success -> {
                            when (
                                val uploaded = profileEditor.updateAvatar(
                                    prepared.value.mimeType,
                                    prepared.value.bytes,
                                )
                            ) {
                                is SocialResult.Failure -> avatarError = uploaded.error.toString()
                                is SocialResult.Success -> {
                                    avatarVersion++
                                    // Bottom bar and Social page show the new photo too.
                                    Injekt.get<SocialOwnProfile>().refresh()
                                }
                            }
                        }
                    }
                } finally {
                    avatarBusy = false
                }
            }
        }
        val changeAvatar: (() -> Unit)? = if (account != null && !avatarBusy) {
            {
                avatarError = null
                avatarPicker.launch("image/*")
            }
        } else {
            null
        }

        LaunchedEffect(account?.userId) {
            avatarError = null
        }

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
                onBrowser = { homeserver, create ->
                    loginError = null
                    scope.launch {
                        when (val result = session.startOAuth(homeserver, create)) {
                            is SocialResult.Failure -> loginError = result.error
                            is SocialResult.Success -> {
                                dialog = null
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, result.value.toUri())
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (_: ActivityNotFoundException) {
                                    loginError = SocialError.Unknown("Aucun navigateur disponible")
                                    dialog = SocialDialog.Login
                                }
                            }
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

        return buildList<Preference> {
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.social_matrix_account),
                    content = {
                        MatrixAccountCard(
                            account = account,
                            status = status,
                            sessionState = sessionState,
                            avatarVersion = avatarVersion,
                            avatarBusy = avatarBusy,
                            avatarError = avatarError,
                            onChangeAvatar = changeAvatar,
                            onClick = {
                                if (account == null) {
                                    loginError = null
                                    dialog = SocialDialog.Login
                                } else {
                                    dialog = SocialDialog.Logout
                                }
                            },
                        )
                    },
                ),
            )
            if (account != null) {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.social_battery_title),
                        subtitle = stringResource(
                            if (batteryUnrestricted) MR.strings.social_battery_granted else MR.strings.social_battery_summary,
                        ),
                        onClick = {
                            if (batteryUnrestricted) {
                                // Already exempted: OEM settings (auto-start, freezer) live here.
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = "package:${context.packageName}".toUri()
                                    },
                                )
                                return@TextPreference
                            }
                            try {
                                @SuppressLint("BatteryLife")
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                batteryLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                context.toast(MR.strings.battery_optimization_setting_activity_not_found)
                            }
                        },
                    ),
                )
            }
            if (account != null) {
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = "Partager mon activité",
                        content = {
                            val activityService = remember { Injekt.get<SocialActivityService>() }
                            val shareEnabled by activityService.shareEnabled.collectAsState()
                            SwitchPreferenceWidget(
                                title = "Partager mon activité",
                                subtitle = "Tes amis voient les œuvres que tu ajoutes à ta bibliothèque",
                                checked = shareEnabled,
                                onCheckedChanged = activityService::setShareEnabled,
                            )
                        },
                    ),
                )
            }
            // Friends now live on the Social tab; this screen only holds account settings.
            add(
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.social_matrix_info),
                ),
            )
        }
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
    onBrowser: (homeserverUrl: String, create: Boolean) -> Unit,
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
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && homeserver.isNotBlank(),
                    onClick = { onBrowser(homeserver.trim(), true) },
                ) {
                    Text("Créer un compte")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && homeserver.isNotBlank(),
                    onClick = { onBrowser(homeserver.trim(), false) },
                ) {
                    Text("Se connecter avec le navigateur")
                }
                Text(
                    text = "Tu reviens dans Shiro déjà connecté. Ou entre tes identifiants :",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun MatrixAccountCard(
    account: SocialAccount?,
    status: String,
    sessionState: SocialSessionState,
    avatarVersion: Int,
    avatarBusy: Boolean,
    avatarError: String?,
    onChangeAvatar: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val avatarBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        account?.userId,
        avatarVersion,
    ) {
        value = if (account != null) {
            avatarLoader.loadOwnAvatar(112)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } else {
            null
        }
    }
    val avatarDescription = if (onChangeAvatar != null) {
        stringResource(MR.strings.social_matrix_change_avatar)
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = sessionState !is SocialSessionState.Authenticating && !avatarBusy,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (onChangeAvatar != null) {
                        Modifier.clickable(onClick = onChangeAvatar)
                    } else {
                        Modifier
                    },
                ),
        ) {
            val bitmap = avatarBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = avatarDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PeopleAlt,
                        contentDescription = avatarDescription,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (avatarBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else if (onChangeAvatar != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MR.strings.social_matrix_account),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            avatarError?.let { error ->
                Text(
                    text = stringResource(MR.strings.social_matrix_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(20.dp),
        )
    }
}

@Composable
private fun FriendCard(
    friend: SocialFriendRecord,
    avatarLoader: SocialAvatarLoader,
    onClick: () -> Unit,
) {
    val avatarBitmap by produceState<ImageBitmap?>(initialValue = null, friend.mxid) {
        value = avatarLoader.loadProfileAvatar(friend.mxid)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = avatarBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PeopleAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.displayName ?: friend.mxid.substringBefore(':').removePrefix("@"),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = friend.mxid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(20.dp),
        )
    }
}

@Composable
private fun SocialFriendsCard(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PeopleAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Amis",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Liste, lien de profil et QR d'ajout",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(20.dp),
        )
    }
}
