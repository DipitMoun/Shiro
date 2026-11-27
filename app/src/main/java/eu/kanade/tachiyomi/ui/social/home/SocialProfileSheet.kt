package eu.kanade.tachiyomi.ui.social.home

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.data.social.activity.SocialActivityService
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialAvatarImageProcessor
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialOwnProfile
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialProfileEditor
import eu.kanade.tachiyomi.data.social.status.SocialStatusService
import eu.kanade.tachiyomi.data.social.status.StatusRules
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * What a long press on the avatar opens: photo, display name, notifications, sharing of the
 * activity and logout. Everything about the account is here, so the page itself stays light.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialProfileSheet(
    account: SocialAccount,
    onDismiss: () -> Unit,
    onShowQr: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { Injekt.get<MatrixSessionManager>() }
    val ownProfile = remember { Injekt.get<SocialOwnProfile>() }
    val profileEditor = remember { Injekt.get<SocialProfileEditor>() }
    val statusService = remember { Injekt.get<SocialStatusService>() }
    val activityService = remember { Injekt.get<SocialActivityService>() }

    val avatarBytes by ownProfile.avatar.collectAsStateWithLifecycle()
    val avatar = remember(avatarBytes) { avatarBytes?.let(::decodeAvatar) }
    val savedName by ownProfile.displayName.collectAsStateWithLifecycle()
    val shareEnabled by activityService.shareEnabled.collectAsStateWithLifecycle()

    val currentName = savedName ?: account.displayName?.takeIf { it.isNotBlank() }.orEmpty()
    var name by remember(currentName) { mutableStateOf(currentName) }
    var savingName by remember { mutableStateOf(false) }
    var avatarBusy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        avatarBusy = true
        scope.launch {
            try {
                val prepared = SocialAvatarImageProcessor.prepare(context, uri)
                if (prepared !is SocialResult.Success) {
                    onMessage("Photo illisible")
                    return@launch
                }
                val uploaded = profileEditor.updateAvatar(prepared.value.mimeType, prepared.value.bytes)
                if (uploaded is SocialResult.Success) ownProfile.refresh() else onMessage("Photo non envoyée, réessaie")
            } finally {
                avatarBusy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !avatarBusy) { picker.launch("image/*") },
                ) {
                    Avatar(bitmap = avatar, size = 84.dp)
                    if (avatarBusy) {
                        Box(
                            Modifier.size(84.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Edit, "Changer la photo", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pseudo") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Text(
                        text = shortHandle(account.userId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
            if (name.trim().isNotEmpty() && name.trim() != currentName) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        savingName = true
                        scope.launch {
                            val trimmed = name.trim()
                            val ok = statusService.setDisplayName(trimmed)
                            if (ok) ownProfile.overrideDisplayName(trimmed)
                            onMessage(if (ok) "Pseudo mis à jour" else "Pseudo non modifié, réessaie")
                            savingName = false
                        }
                    },
                    enabled = !savingName,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (savingName) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Enregistrer le pseudo")
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            SheetRow(
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                subtitle = "Messages, demandes, activité et stories",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            SheetRow(
                icon = Icons.Outlined.Visibility,
                title = "Partager mon activité",
                subtitle = "Tes amis voient les œuvres que tu ajoutes",
                onClick = { activityService.setShareEnabled(!shareEnabled) },
                trailing = { Switch(checked = shareEnabled, onCheckedChange = activityService::setShareEnabled) },
            )
            SheetRow(
                icon = Icons.Outlined.QrCode2,
                title = "Mon QR",
                subtitle = "Pour qu'un ami t'ajoute",
                onClick = { onDismiss(); onShowQr() },
            )
            HorizontalDivider()
            SheetRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = "Se déconnecter",
                subtitle = null,
                onClick = { confirmLogout = true },
                danger = true,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("Tu ne recevras plus les messages et les stories de tes amis tant que tu n'es pas reconnecté.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLogout = false
                        onDismiss()
                        scope.launch { session.logout() }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Se déconnecter") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmLogout = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** Edit or clear the status shown under the avatar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialStatusSheet(
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Ton statut", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Tes amis le voient à côté de ta photo pendant 24 h.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(StatusRules.MAX_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ex. : en plein marathon Frieren") },
                supportingText = { Text("${text.length}/${StatusRules.MAX_LENGTH}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                if (!current.isNullOrBlank()) {
                    TextButton(onClick = { onSave(null) }) { Text("Effacer") }
                }
                Button(onClick = { onSave(text) }, enabled = StatusRules.clean(text).isNotEmpty()) { Text("Publier") }
            }
        }
    }
}
