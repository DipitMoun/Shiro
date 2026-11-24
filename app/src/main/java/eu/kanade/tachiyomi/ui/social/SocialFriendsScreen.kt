package eu.kanade.tachiyomi.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import eu.kanade.tachiyomi.data.social.friends.SocialFriendRemover
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.friends.SocialRoomInvite
import eu.kanade.tachiyomi.data.social.friends.SocialProfileLink
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.directory.SocialDirectoryUser
import eu.kanade.tachiyomi.data.social.matrix.directory.SocialUserDirectory
import eu.kanade.tachiyomi.data.social.matrix.directory.SocialUserSearchPage
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val MIN_SEARCH_CHARS = 3

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
        val friendRemover = remember { Injekt.get<SocialFriendRemover>() }
        var pendingRemoval by remember { mutableStateOf<SocialFriendRecord?>(null) }
        var removing by remember { mutableStateOf(false) }
        val roomService = remember { Injekt.get<SocialRoomService>() }
        val directory = remember { Injekt.get<SocialUserDirectory>() }
        val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
        val sessionState by session.state.collectAsStateWithLifecycle()
        val friends by friendsStore.friends.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val account = (sessionState as? SocialSessionState.Authenticated)?.account
        val ownLink = account?.let {
            SocialProfileLink.encode(it.userId, it.displayName)
        }.orEmpty()

        var addInput by remember { mutableStateOf(prefillLink.orEmpty()) }
        var searchQuery by remember { mutableStateOf("") }
        var addingMxid by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        val inviteMonitor = remember { Injekt.get<SocialInviteMonitor>() }
        val pendingInvites by inviteMonitor.invites.collectAsStateWithLifecycle()

        pendingRemoval?.let { friend ->
            RemoveFriendDialog(
                friend = friend,
                removing = removing,
                onConfirm = {
                    scope.launch {
                        removing = true
                        val result = friendRemover.unfriend(friend.mxid)
                        removing = false
                        pendingRemoval = null
                        message = if (result is SocialResult.Success) {
                            "Ami retiré"
                        } else {
                            "Ami retiré de ta liste, mais certains salons n'ont pas pu être nettoyés"
                        }
                    }
                },
                onDismiss = { pendingRemoval = null },
            )
        }

        val searchState by produceState<SocialUserSearchState>(
            initialValue = SocialUserSearchState.Idle,
            searchQuery,
            account?.userId,
        ) {
            val query = searchQuery.trim()
            if (account == null || query.length < MIN_SEARCH_CHARS) {
                value = SocialUserSearchState.Idle
            } else {
                value = SocialUserSearchState.Loading
                delay(350)
                value = when (val result = directory.search(query)) {
                    is SocialResult.Success -> SocialUserSearchState.Results(result.value)
                    is SocialResult.Failure -> SocialUserSearchState.Error(result.error)
                }
            }
        }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            MatrixSessionBootstrap.ensureRestored(session)
        }

        androidx.compose.runtime.LaunchedEffect(account?.userId) {
            if (account != null) inviteMonitor.refresh()
        }

        fun addFriend(record: SocialFriendRecord): Boolean {
            val self = account
            if (self == null) {
                message = "Connecte d’abord un compte Matrix"
                return false
            }
            if (record.mxid == self.userId) {
                message = "C’est ton propre compte Matrix"
                return false
            }
            if (addingMxid != null) return false

            addingMxid = record.mxid
            scope.launch {
                try {
                    friendsStore.add(record)
                    when (val invited = roomService.inviteFriendToOwnRooms(record.mxid, self.userId)) {
                        is SocialResult.Failure -> message = "Ami ajouté, invitation échouée : ${invited.error}"
                        is SocialResult.Success -> message = "Ami ajouté et invité dans tes rooms"
                    }
                } finally {
                    addingMxid = null
                }
            }
            return true
        }

        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            val raw = result.contents ?: return@rememberLauncherForActivityResult
            val record = SocialProfileLink.decode(raw)
            if (record == null) {
                message = "QR ou lien invalide"
            } else {
                addFriend(record)
            }
        }

        fun addFromText() {
            val record = SocialProfileLink.decode(addInput)
            if (record == null) {
                message = "Colle un lien Shiro valide"
                return
            }
            if (addFriend(record)) {
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
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Rechercher un compte Matrix") },
                        placeholder = { Text("Pseudo ou @utilisateur:serveur") },
                        singleLine = true,
                        enabled = account != null,
                        trailingIcon = {
                            if (searchState is SocialUserSearchState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                    )
                }

                when (val state = searchState) {
                    SocialUserSearchState.Idle -> {
                        if (
                            account != null &&
                            searchQuery.isNotBlank() &&
                            searchQuery.trim().length < MIN_SEARCH_CHARS
                        ) {
                            item {
                                Text(
                                    text = "Tape au moins $MIN_SEARCH_CHARS caractères.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    SocialUserSearchState.Loading -> Unit
                    is SocialUserSearchState.Results -> {
                        if (state.page.users.isEmpty()) {
                            item {
                                Text(
                                    text = "Aucun compte trouvé.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(state.page.users, key = { "search-${it.mxid}" }) { user ->
                                DirectoryUserRow(
                                    user = user,
                                    avatarLoader = avatarLoader,
                                    isSelf = user.mxid == account?.userId,
                                    isFriend = friends.any { it.mxid == user.mxid },
                                    isAdding = addingMxid == user.mxid,
                                    canAdd = addingMxid == null,
                                    onAdd = {
                                        addFriend(
                                            SocialFriendRecord(
                                                mxid = user.mxid,
                                                displayName = user.displayName,
                                            ),
                                        )
                                    },
                                )
                            }
                            if (state.page.limited) {
                                item {
                                    Text(
                                        text = "Résultats limités. Précise ta recherche.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    is SocialUserSearchState.Error -> {
                        item {
                            Text(
                                text = "Recherche impossible : ${state.error}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        modifier = Modifier.fillMaxWidth(),
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
                                    scope.launch { inviteMonitor.accept(invite.roomId) }
                                },
                            ) {
                                Text("Rejoindre")
                            }
                            TextButton(
                                onClick = {
                                    scope.launch { inviteMonitor.decline(invite.roomId) }
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
                        onRemove = { pendingRemoval = friend },
                    )
                }
            }
        }
    }
}

/**
 * Removing a friend also leaves their rooms and removes them from ours, so it is confirmed.
 */
@Composable
private fun RemoveFriendDialog(
    friend: SocialFriendRecord,
    removing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = friend.displayName ?: friend.mxid
    AlertDialog(
        onDismissRequest = { if (!removing) onDismiss() },
        title = { Text("Retirer $name ?") },
        text = {
            Text(
                "Tu quittes ses salons et il n'aura plus accès aux tiens. " +
                    "Pour redevenir amis, il faudra une nouvelle demande d'ami.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !removing) {
                if (removing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Retirer")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !removing) { Text("Annuler") }
        },
    )
}

private sealed interface SocialUserSearchState {
    data object Idle : SocialUserSearchState
    data object Loading : SocialUserSearchState
    data class Results(val page: SocialUserSearchPage) : SocialUserSearchState
    data class Error(val error: SocialError) : SocialUserSearchState
}

@Composable
private fun DirectoryUserRow(
    user: SocialDirectoryUser,
    avatarLoader: SocialAvatarLoader,
    isSelf: Boolean,
    isFriend: Boolean,
    isAdding: Boolean,
    canAdd: Boolean,
    onAdd: () -> Unit,
) {
    val avatarBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        user.mxid,
        user.avatarUrl,
    ) {
        value = user.avatarUrl?.let { url ->
            avatarLoader.loadAvatar(url, 96)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }
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
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = avatarBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = (user.displayName ?: user.mxid)
                        .substringBefore(':')
                        .removePrefix("@")
                        .firstOrNull()
                        ?.uppercaseChar()
                        ?.toString()
                        ?: "?",
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = user.displayName ?: user.mxid.substringBefore(':').removePrefix("@"),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user.mxid,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            isSelf -> Text(
                text = "Ton compte",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            isFriend -> Text(
                text = "Déjà ami",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            isAdding -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            else -> TextButton(onClick = onAdd, enabled = canAdd) {
                Text("Ajouter")
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

internal fun makeQrCode(content: String, size: Int = 512): Bitmap? = try {
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


