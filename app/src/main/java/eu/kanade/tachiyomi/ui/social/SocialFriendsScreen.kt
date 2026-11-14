package eu.kanade.tachiyomi.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.social.friends.SocialFriendInviteAccepter
import eu.kanade.tachiyomi.data.social.friends.SocialFriendRecord
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.friends.SocialRoomInvite
import eu.kanade.tachiyomi.data.social.friends.SocialProfileLink
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SocialFriendsScreen(
    private val prefillLink: String? = null,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val session = remember { Injekt.get<MatrixSessionManager>() }
        val friendsStore = remember { Injekt.get<SocialFriendStore>() }
        val roomService = remember { Injekt.get<SocialRoomService>() }
        val inviteAccepter = remember { Injekt.get<SocialFriendInviteAccepter>() }
        val sessionState by session.state.collectAsStateWithLifecycle()
        val friends by friendsStore.friends.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val account = (sessionState as? SocialSessionState.Authenticated)?.account
        val ownLink = account?.let {
            SocialProfileLink.encode(it.userId, it.displayName)
        }.orEmpty()

        var addInput by remember { mutableStateOf(prefillLink.orEmpty()) }
        var message by remember { mutableStateOf<String?>(null) }
        var pendingInvites by remember { mutableStateOf<List<SocialRoomInvite>>(emptyList()) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (session.state.value is SocialSessionState.Unauthenticated) {
                session.restore()
            }
        }

        androidx.compose.runtime.LaunchedEffect(account?.userId) {
            if (account != null) {
                inviteAccepter.acceptInvitesFromFriends()
                pendingInvites = (inviteAccepter.pendingInvites() as? SocialResult.Success)
                    ?.value
                    .orEmpty()
            }
        }

        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            val raw = result.contents ?: return@rememberLauncherForActivityResult
            val record = SocialProfileLink.decode(raw)
            if (record == null) {
                message = "QR ou lien invalide"
            } else if (account == null) {
                message = "Connecte d’abord un compte Matrix"
            } else if (record.mxid == account.userId) {
                message = "C’est ton propre compte Matrix"
            } else {
                scope.launch {
                    friendsStore.add(record)
                    when (val invited = roomService.inviteFriendToOwnRooms(record.mxid, account.userId)) {
                        is SocialResult.Failure -> message = "Ami ajouté, invitation échouée : ${invited.error}"
                        is SocialResult.Success -> message = "Ami ajouté et invité dans tes rooms"
                    }
                }
            }
        }

        fun addFromText() {
            val record = SocialProfileLink.decode(addInput)
            if (record == null) {
                message = "Colle un lien Shiro valide"
                return
            }
            if (account == null) {
                message = "Connecte d’abord un compte Matrix"
                return
            }
            if (record.mxid == account.userId) {
                message = "C’est ton propre compte Matrix"
                return
            }
            scope.launch {
                friendsStore.add(record)
                when (val invited = roomService.inviteFriendToOwnRooms(record.mxid, account.userId)) {
                    is SocialResult.Failure -> message = "Ami ajouté, invitation échouée : ${invited.error}"
                    is SocialResult.Success -> message = "Ami ajouté et invité dans tes rooms"
                }
                addInput = ""
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Amis") },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "Mon profil Shiro",
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    if (ownLink.isNotEmpty()) {
                        val qr = remember(ownLink) { makeQrCode(ownLink) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (qr != null) {
                                Image(
                                    bitmap = qr.asImageBitmap(),
                                    contentDescription = "QR de mon profil Shiro",
                                    modifier = Modifier
                                        .size(150.dp)
                                        .background(androidx.compose.ui.graphics.Color.White)
                                        .padding(8.dp),
                                )
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(account?.userId.orEmpty(), fontSize = 12.sp)
                                Text(
                                    text = ownLink,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(
                                            ClipboardManager::class.java,
                                        )
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText("Shiro profile", ownLink),
                                        )
                                        message = "Lien copié"
                                    },
                                ) {
                                    Text("Copier le lien")
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Aucun compte Matrix connecté.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }

                item {
                    Text(
                        text = "Ajouter un ami",
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Lien de profil Shiro") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = ::addFromText,
                            enabled = addInput.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Ajouter")
                        }
                        Button(
                            onClick = {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scanne le QR Shiro")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(false),
                                )
                            },
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                            Text("QR", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                message?.let { text ->
                    item {
                        Text(
                            text = text,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }

                if (pendingInvites.isNotEmpty()) {
                    item {
                        Text(
                            text = "Invitations Matrix",
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                    items(pendingInvites, key = { it.roomId }) { invite ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(invite.name, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text(
                                    invite.inviterMxid ?: invite.roomId,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        inviteAccepter.acceptInvite(invite.roomId)
                                        invite.inviterMxid?.let { inviter ->
                                            if (account != null && inviter != account.userId) {
                                                friendsStore.add(SocialFriendRecord(mxid = inviter))
                                                roomService.inviteFriendToOwnRooms(
                                                    friendMxid = inviter,
                                                    selfMxid = account.userId,
                                                )
                                            }
                                        }
                                        pendingInvites = pendingInvites.filterNot { it.roomId == invite.roomId }
                                    }
                                },
                            ) {
                                Text("Rejoindre")
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        inviteAccepter.declineInvite(invite.roomId)
                                        pendingInvites = pendingInvites.filterNot { it.roomId == invite.roomId }
                                    }
                                },
                            ) {
                                Text("Refuser")
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Mes amis (${friends.size})",
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
                if (friends.isEmpty()) {
                    item {
                        Text(
                            text = "Aucun ami. Partage ton lien ou scanne celui d’un proche.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(friends, key = { it.mxid }) { friend ->
                    FriendRow(
                        friend = friend,
                        onRemove = {
                            scope.launch {
                                friendsStore.remove(friend.mxid)
                                message = "Ami retiré"
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: SocialFriendRecord,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = friend.displayName ?: friend.mxid.substringBefore(':').removePrefix("@"),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            Text(
                text = friend.mxid,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Retirer")
        }
    }
}

private fun makeQrCode(content: String, size: Int = 512): Bitmap? = try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
} catch (_: Throwable) {
    null
}


