package eu.kanade.tachiyomi.ui.main

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.friends.SocialRoomInvite
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Global popup that shows one pending friend invitation at a time.
 *
 * Any Matrix room invite coming from a non-friend mxid is treated as an implicit friendship
 * request. The list comes from [SocialInviteMonitor], fed live by the sync service, so a
 * request appears while the app is open, without restarting it.
 */
@Composable
internal fun SocialFriendInvitePopup() {
    val monitor = remember { Injekt.get<SocialInviteMonitor>() }
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val scope = rememberCoroutineScope()

    val pendingInvites by monitor.invites.collectAsState()

    // The sync service may not run yet (first launch, login just done): read once too.
    LaunchedEffect(Unit) { monitor.refresh() }

    val currentInvite = pendingInvites.firstOrNull() ?: return

    SocialFriendInviteDialog(
        invite = currentInvite,
        avatarLoader = avatarLoader,
        onAccept = { scope.launch { monitor.accept(currentInvite.roomId) } },
        onDecline = { scope.launch { monitor.decline(currentInvite.roomId) } },
    )
}

@Composable
private fun SocialFriendInviteDialog(
    invite: SocialRoomInvite,
    avatarLoader: SocialAvatarLoader,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val avatarBytes by produceState<ByteArray?>(initialValue = null, invite.inviterMxid) {
        value = invite.inviterMxid?.let { avatarLoader.loadProfileAvatar(it) }
    }
    val bitmap = remember(avatarBytes) {
        avatarBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    AlertDialog(
        // Tapping outside must not decline a friend request by accident.
        onDismissRequest = {},
        icon = if (bitmap != null) {
            {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                )
            }
        } else null,
        title = { Text("Demande d'ami") },
        text = {
            Column {
                Text(
                    text = invite.inviterDisplayName
                        ?: invite.inviterMxid?.substringBefore(':')?.removePrefix("@")
                        ?: "Utilisateur inconnu",
                    style = MaterialTheme.typography.titleMedium,
                )
                invite.inviterMxid?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("veut t'ajouter comme ami et t'inviter dans ses salons.")
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("Accepter") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Refuser") }
        },
    )
}