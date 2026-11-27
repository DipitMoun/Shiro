package eu.kanade.tachiyomi.ui.social.home

import eu.kanade.tachiyomi.data.social.activity.SocialActivityItem
import eu.kanade.tachiyomi.data.social.WorkSourceRef
import eu.kanade.tachiyomi.data.social.ExtensionInstallHelper
import eu.kanade.presentation.social.SourceOpenResult
import eu.kanade.presentation.social.MissingExtensionDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import eu.kanade.tachiyomi.data.social.status.SocialStatusService
import eu.kanade.tachiyomi.data.social.story.StoryReel
import eu.kanade.tachiyomi.ui.social.story.StatusPill
import eu.kanade.tachiyomi.ui.social.story.StoryComposerScreen
import eu.kanade.tachiyomi.ui.social.story.StoryRing
import eu.kanade.tachiyomi.ui.social.story.StoryViewerScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.presentation.more.settings.screen.SettingsSocialScreen
import eu.kanade.presentation.social.FriendLibraryScreen
import eu.kanade.presentation.social.SocialWorkOpener
import eu.kanade.tachiyomi.data.social.activity.SocialActivityKind
import eu.kanade.tachiyomi.data.social.activity.SocialActivityService
import eu.kanade.tachiyomi.data.social.matrix.inbox.SocialInbox
import eu.kanade.tachiyomi.data.social.friends.SocialFriendRecord
import eu.kanade.tachiyomi.data.social.friends.SocialFriendRemover
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.friends.SocialProfileLink
import eu.kanade.tachiyomi.data.social.friends.SocialRoomInvite
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.directory.SocialDirectoryUser
import eu.kanade.tachiyomi.data.social.matrix.directory.SocialUserDirectory
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.profile.SocialOwnProfile
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import eu.kanade.tachiyomi.ui.social.makeQrCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val MIN_SEARCH_CHARS = 3
private const val TAB_SOCIAL = 0
private const val TAB_MESSAGES = 1
private const val MAX_FEED_ITEMS = 30

/** "Messages" with the total unread count, from the inbox. */
@Composable
private fun MessagesTabLabel() {
    val inbox = remember { Injekt.get<SocialInbox>() }
    val conversations by inbox.conversations.collectAsStateWithLifecycle()
    val unread = conversations.orEmpty().sumOf { it.unreadCount }
    LaunchedEffect(Unit) { inbox.refresh() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Messages")
        if (unread > 0) {
            Spacer(Modifier.width(6.dp))
            Badge { Text(if (unread > 99) "99+" else unread.toString()) }
        }
    }
}

internal fun decodeAvatar(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/** `@kev` on matrix.org, the full id elsewhere: the server is noise for most people. */
internal fun shortHandle(mxid: String): String =
    if (mxid.endsWith(":matrix.org")) mxid.substringBefore(':') else mxid

/**
 * Social home: who you are, who wants to be your friend, who your friends are.
 * Account details and notification settings live behind the gear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialHomeContent() {
    val navigator = LocalNavigator.currentOrThrow
    val session = remember { Injekt.get<MatrixSessionManager>() }
    val sessionState by session.state.collectAsStateWithLifecycle()
    val account = (sessionState as? SocialSessionState.Authenticated)?.account
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAddSheet by remember { mutableStateOf(false) }
    var showQrSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(TAB_SOCIAL) }

    LaunchedEffect(Unit) { MatrixSessionBootstrap.ensureRestored(session) }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Social") },
                    actions = {
                        IconButton(onClick = { navigator.push(SettingsSocialScreen()) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Réglages sociaux")
                        }
                    },
                )
                if (account != null) {
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == TAB_SOCIAL,
                            onClick = { selectedTab = TAB_SOCIAL },
                            text = { Text("Social") },
                        )
                        Tab(
                            selected = selectedTab == TAB_MESSAGES,
                            onClick = { selectedTab = TAB_MESSAGES },
                            text = { MessagesTabLabel() },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (account == null) {
            SignedOutState(
                connecting = sessionState is SocialSessionState.Authenticating,
                onConnect = { navigator.push(SettingsSocialScreen()) },
                modifier = Modifier.padding(padding),
            )
        } else if (selectedTab == TAB_MESSAGES) {
            MessagesContent(contentPadding = padding)
        } else {
            SignedInContent(
                account = account,
                contentPadding = padding,
                onShowQr = { showQrSheet = true },
                onAddFriend = { showAddSheet = true },
                onMessage = ::toast,
            )
        }
    }

    if (showAddSheet && account != null) {
        AddFriendSheet(account = account, onDismiss = { showAddSheet = false }, onMessage = ::toast)
    }
    if (showQrSheet && account != null) {
        MyQrSheet(account = account, onDismiss = { showQrSheet = false }, onMessage = ::toast)
    }
}

@Composable
private fun SignedOutState(connecting: Boolean, onConnect: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PeopleAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Regarde avec tes amis",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Partage des moments, réagis aux épisodes et découvre ce que regardent tes proches.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConnect, enabled = !connecting) {
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Connecter mon compte")
            }
        }
    }
}

@Composable
private fun SignedInContent(
    account: SocialAccount,
    contentPadding: PaddingValues,
    onShowQr: () -> Unit,
    onAddFriend: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val friendsStore = remember { Injekt.get<SocialFriendStore>() }
    val inviteMonitor = remember { Injekt.get<SocialInviteMonitor>() }
    val friendRemover = remember { Injekt.get<SocialFriendRemover>() }
    val friends by friendsStore.friends.collectAsStateWithLifecycle()
    val invites by inviteMonitor.invites.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var pendingRemoval by remember { mutableStateOf<SocialFriendRecord?>(null) }
    var removing by remember { mutableStateOf(false) }

    val activityService = remember { Injekt.get<SocialActivityService>() }
    val storyService = remember { Injekt.get<eu.kanade.tachiyomi.data.social.story.SocialStoryService>() }
    val statusService = remember { Injekt.get<SocialStatusService>() }
    val reels by storyService.reels.collectAsStateWithLifecycle()
    val statuses by statusService.statuses.collectAsStateWithLifecycle()
    var showProfileSheet by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }
    val feed by activityService.feed.collectAsStateWithLifecycle()
    val workOpener = remember { SocialWorkOpener() }
    var openingWork by remember { mutableStateOf(false) }
    var missingExtension by remember { mutableStateOf<Pair<WorkSourceRef, SocialActivityItem>?>(null) }
    var installingExtension by remember { mutableStateOf(false) }

    LaunchedEffect(account.userId) {
        inviteMonitor.refresh()
        activityService.start()
        activityService.refreshFeed()
        storyService.start()
        storyService.refresh()
        // The story rooms are known once the stories are read.
        statusService.refresh()
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "profile") {
            val ownReel = reels.firstOrNull { it.authorMxid == account.userId }
            SelfHeader(
                account = account,
                hasStory = ownReel != null,
                status = statuses[account.userId],
                onStory = {
                    if (ownReel != null) navigator.push(StoryViewerScreen(account.userId)) else navigator.push(StoryComposerScreen())
                },
                onAddStory = { navigator.push(StoryComposerScreen()) },
                onProfile = { showProfileSheet = true },
                onStatus = { showStatusSheet = true },
            )
        }

        if (invites.isNotEmpty()) {
            item(key = "requests-title") { SectionTitle("Demandes", count = invites.size) }
            items(invites, key = { "request-${it.roomId}" }) { invite ->
                FriendRequestRow(
                    invite = invite,
                    onAccept = { scope.launch { inviteMonitor.accept(invite.roomId) } },
                    onDecline = { scope.launch { inviteMonitor.decline(invite.roomId) } },
                )
            }
        }

        item(key = "friends-title") {
            SectionTitle(
                title = "Amis",
                count = friends.size,
                action = {
                    TextButton(onClick = onAddFriend) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ajouter")
                    }
                },
            )
        }
        item(key = "friends") {
            if (friends.isEmpty()) {
                EmptyFriends(onAddFriend = onAddFriend)
            } else {
                val reelByAuthor = reels.associateBy { it.authorMxid }
                val reserveStatus = friends.any { statuses[it.mxid] != null }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(friends, key = { it.mxid }) { friend ->
                        FriendBubble(
                            friend = friend,
                            reel = reelByAuthor[friend.mxid],
                            status = statuses[friend.mxid],
                            reserveStatus = reserveStatus,
                            onStory = { navigator.push(StoryViewerScreen(friend.mxid)) },
                            onOpen = {
                                navigator.push(
                                    FriendLibraryScreen(authorMxid = friend.mxid, displayName = friend.displayName),
                                )
                            },
                            onRemove = { pendingRemoval = friend },
                        )
                    }
                }
            }
        }

        item(key = "activity-title") { SectionTitle("Activité") }
        if (feed.isEmpty()) {
            item(key = "activity-empty") {
                Text(
                    text = "Les œuvres que tes amis ajoutent à leur bibliothèque apparaîtront ici.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(feed.take(MAX_FEED_ITEMS), key = { "activity-${it.eventId}" }) { activity ->
                val actor = friends.firstOrNull { it.mxid == activity.actorMxid }
                ActivityRow(
                    activity = activity,
                    actorName = actor?.displayName?.takeIf { it.isNotBlank() } ?: shortHandle(activity.actorMxid),
                    showActor = true,
                    inLibrary = activityService.isInLibrary(activity.title),
                    onClick = {
                        if (openingWork) return@ActivityRow
                        openingWork = true
                        scope.launch {
                            val ref = activity.source
                            val exact = ref?.let { workOpener.openBySource(it) }
                            if (exact is SourceOpenResult.Open) {
                                openingWork = false
                                navigator.push(exact.screen)
                                return@launch
                            }
                            if (exact is SourceOpenResult.MissingExtension) {
                                openingWork = false
                                missingExtension = exact.ref to activity
                                return@launch
                            }
                            val screen = workOpener.open(
                                activity.title,
                                isManga = activity.kind == SocialActivityKind.MANGA_ADDED,
                            )
                            openingWork = false
                            if (screen != null) navigator.push(screen) else onMessage("Œuvre introuvable")
                        }
                    },
                )
            }
        }
    }

    if (showProfileSheet) {
        SocialProfileSheet(
            account = account,
            onDismiss = { showProfileSheet = false },
            onShowQr = onShowQr,
            onMessage = onMessage,
        )
    }
    if (showStatusSheet) {
        SocialStatusSheet(
            current = statuses[account.userId],
            onDismiss = { showStatusSheet = false },
            onSave = { text ->
                showStatusSheet = false
                scope.launch {
                    val ok = statusService.setStatus(text)
                    onMessage(
                        when {
                            !ok -> "Statut non publié : ton salon de stories n'est pas encore prêt, réessaie dans un instant"
                            text.isNullOrBlank() -> "Statut effacé"
                            else -> "Statut publié"
                        },
                    )
                }
            },
        )
    }

    missingExtension?.let { (ref, activity) ->
        MissingExtensionDialog(
            ref = ref,
            installing = installingExtension,
            onInstall = {
                installingExtension = true
                scope.launch {
                    val started = runCatching { ExtensionInstallHelper.install(ref) }.getOrDefault(false)
                    installingExtension = false
                    missingExtension = null
                    onMessage(
                        if (started) "Installation de l'extension lancée, rouvre l'œuvre ensuite"
                        else "Extension introuvable dans tes dépôts : ajoute le bon dépôt d'abord",
                    )
                }
            },
            onSearchInstead = {
                missingExtension = null
                scope.launch {
                    val screen = workOpener.open(activity.title, isManga = activity.kind == SocialActivityKind.MANGA_ADDED)
                    if (screen != null) navigator.push(screen) else onMessage("Œuvre introuvable")
                }
            },
            onDismiss = { missingExtension = null },
        )
    }

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
                    onMessage(
                        if (result is SocialResult.Success) {
                            "${friend.displayName ?: shortHandle(friend.mxid)} retiré de tes amis"
                        } else {
                            "Retiré de ta liste, mais certains salons n'ont pas pu être nettoyés"
                        },
                    )
                }
            },
            onDismiss = { if (!removing) pendingRemoval = null },
        )
    }
}

/**
 * You, in one line: the avatar with the ring of your story, your name, your status. Tap the avatar
 * for your story, hold it for everything about the account.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelfHeader(
    account: SocialAccount,
    hasStory: Boolean,
    status: String?,
    onStory: () -> Unit,
    onAddStory: () -> Unit,
    onProfile: () -> Unit,
    onStatus: () -> Unit,
) {
    val ownProfile = remember { Injekt.get<SocialOwnProfile>() }
    val avatarBytes by ownProfile.avatar.collectAsStateWithLifecycle()
    val avatar = remember(avatarBytes) { avatarBytes?.let(::decodeAvatar) }
    val profileName by ownProfile.displayName.collectAsStateWithLifecycle()
    val name = profileName ?: account.displayName?.takeIf { it.isNotBlank() } ?: shortHandle(account.userId)
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            StoryRing(
                ringed = hasStory,
                unseen = false,
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onStory,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onProfile()
                        },
                    ),
            ) {
                Avatar(bitmap = avatar, size = 56.dp)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                    .clickable(onClick = onAddStory),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, "Ajouter une story", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            StatusPill(
                text = status ?: "Ajouter un statut",
                placeholder = status == null,
                onClick = onStatus,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int? = null, action: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count != null) "$title ($count)" else title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

@Composable
private fun FriendRequestRow(invite: SocialRoomInvite, onAccept: () -> Unit, onDecline: () -> Unit) {
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val avatar by produceState<ImageBitmap?>(null, invite.inviterMxid) {
        value = invite.inviterMxid?.let { avatarLoader.loadProfileAvatar(it, 144) }?.let(::decodeAvatar)
    }
    var busy by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(bitmap = avatar, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = invite.inviterDisplayName
                    ?: invite.inviterMxid?.let(::shortHandle)
                    ?: "Quelqu'un",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "veut t'ajouter en ami",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { busy = true; onDecline() }) {
                Icon(Icons.Outlined.Close, contentDescription = "Refuser")
            }
            FilledTonalIconButton(onClick = { busy = true; onAccept() }) {
                Icon(Icons.Outlined.Check, contentDescription = "Accepter")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendBubble(
    friend: SocialFriendRecord,
    reel: StoryReel?,
    status: String?,
    reserveStatus: Boolean,
    onStory: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val avatar by produceState<ImageBitmap?>(null, friend.mxid) {
        value = avatarLoader.loadProfileAvatar(friend.mxid, 192)?.let(::decodeAvatar)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val name = friend.displayName?.takeIf { it.isNotBlank() } ?: shortHandle(friend.mxid)

    Box {
        Column(
            modifier = Modifier
                .width(76.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    // A story to watch comes first; the library is one long press away.
                    onClick = if (reel != null) onStory else onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (reserveStatus) {
                Box(Modifier.height(26.dp), contentAlignment = Alignment.BottomCenter) {
                    if (status != null) StatusPill(text = status, maxWidth = 76.dp)
                }
            }
            StoryRing(ringed = reel != null, unseen = reel?.hasUnseen == true) {
                Avatar(bitmap = avatar, size = 60.dp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Voir sa bibliothèque") },
                onClick = {
                    menuOpen = false
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = { Text("Retirer des amis", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun EmptyFriends(onAddFriend: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ajoute tes amis pour partager tes moments et voir ce qu'ils regardent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAddFriend) { Text("Ajouter un ami") }
        }
    }
}

@Composable
internal fun Avatar(bitmap: ImageBitmap?, size: Dp) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
private fun RemoveFriendDialog(
    friend: SocialFriendRecord,
    removing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = friend.displayName ?: shortHandle(friend.mxid)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retirer $name ?") },
        text = {
            Text(
                "Tu quittes ses salons et il n'aura plus accès aux tiens. " +
                    "Pour redevenir amis, il faudra une nouvelle demande.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !removing) {
                if (removing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Retirer", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !removing) { Text("Annuler") }
        },
    )
}

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val users: List<SocialDirectoryUser>, val limited: Boolean) : SearchState
    data class Error(val error: SocialError) : SearchState
}

/**
 * One field for everything: a name, an `@id` or a pasted Shiro link. Links and full ids are
 * recognised and offered directly; anything else searches the Matrix user directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFriendSheet(account: SocialAccount, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val friendsStore = remember { Injekt.get<SocialFriendStore>() }
    val roomService = remember { Injekt.get<SocialRoomService>() }
    val directory = remember { Injekt.get<SocialUserDirectory>() }
    val friends by friendsStore.friends.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var addingMxid by remember { mutableStateOf<String?>(null) }

    val direct = remember(query) {
        val text = query.trim()
        if (text.startsWith("shiro://") || (text.startsWith("@") && ':' in text)) {
            SocialProfileLink.decode(text)
        } else {
            null
        }
    }

    val search by produceState<SearchState>(SearchState.Idle, query, direct) {
        val text = query.trim()
        if (direct != null || text.length < MIN_SEARCH_CHARS) {
            value = SearchState.Idle
            return@produceState
        }
        value = SearchState.Loading
        delay(350)
        value = when (val result = directory.search(text)) {
            is SocialResult.Success -> SearchState.Results(result.value.users, result.value.limited)
            is SocialResult.Failure -> SearchState.Error(result.error)
        }
    }

    fun add(record: SocialFriendRecord) {
        when {
            record.mxid == account.userId -> {
                onMessage("C'est ton propre compte")
                return
            }
            friends.any { it.mxid == record.mxid } -> {
                onMessage("Vous êtes déjà amis")
                return
            }
            addingMxid != null -> return
        }
        addingMxid = record.mxid
        scope.launch {
            try {
                friendsStore.add(record)
                val name = record.displayName ?: shortHandle(record.mxid)
                when (roomService.inviteFriendToOwnRooms(record.mxid, account.userId)) {
                    is SocialResult.Success -> onMessage("Demande envoyée à $name")
                    is SocialResult.Failure -> onMessage("$name ajouté, mais l'invitation a échoué")
                }
                onDismiss()
            } finally {
                addingMxid = null
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        val record = SocialProfileLink.decode(raw)
        if (record == null) onMessage("Ce QR n'est pas un profil Shiro") else add(record)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Ajouter un ami", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Pseudo, @identifiant ou lien Shiro") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (search is SearchState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scanne le QR d'un profil Shiro")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scanner un QR")
                }
                OutlinedButton(onClick = { shareProfile(context, account) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Partager mon profil")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (direct != null) {
                item(key = "direct") {
                    UserResultRow(
                        mxid = direct.mxid,
                        displayName = direct.displayName,
                        avatarUrl = null,
                        isFriend = friends.any { it.mxid == direct.mxid },
                        isSelf = direct.mxid == account.userId,
                        isAdding = addingMxid == direct.mxid,
                        onAdd = { add(direct) },
                    )
                }
            }
            when (val state = search) {
                SearchState.Idle, SearchState.Loading -> Unit
                is SearchState.Error -> item(key = "error") {
                    HintText("Recherche impossible pour le moment", isError = true)
                }
                is SearchState.Results -> {
                    if (state.users.isEmpty()) {
                        item(key = "empty") { HintText("Aucun compte trouvé") }
                    }
                    items(state.users, key = { "user-${it.mxid}" }) { user ->
                        UserResultRow(
                            mxid = user.mxid,
                            displayName = user.displayName,
                            avatarUrl = user.avatarUrl,
                            isFriend = friends.any { it.mxid == user.mxid },
                            isSelf = user.mxid == account.userId,
                            isAdding = addingMxid == user.mxid,
                            onAdd = { add(SocialFriendRecord(mxid = user.mxid, displayName = user.displayName)) },
                        )
                    }
                    if (state.limited) {
                        item(key = "limited") { HintText("Résultats limités, précise ta recherche") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun UserResultRow(
    mxid: String,
    displayName: String?,
    avatarUrl: String?,
    isFriend: Boolean,
    isSelf: Boolean,
    isAdding: Boolean,
    onAdd: () -> Unit,
) {
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val avatar by produceState<ImageBitmap?>(null, mxid, avatarUrl) {
        val bytes = if (avatarUrl != null) {
            avatarLoader.loadAvatar(avatarUrl, 144)
        } else {
            avatarLoader.loadProfileAvatar(mxid, 144)
        }
        value = bytes?.let(::decodeAvatar)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(bitmap = avatar, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName?.takeIf { it.isNotBlank() } ?: shortHandle(mxid),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shortHandle(mxid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            isSelf -> Text("Toi", style = MaterialTheme.typography.labelMedium)
            isFriend -> Text("Ami", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            isAdding -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> FilledTonalButton(onClick = onAdd) { Text("Ajouter") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyQrSheet(account: SocialAccount, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val link = remember(account) { SocialProfileLink.encode(account.userId, account.displayName) }
    val qr = remember(link) { makeQrCode(link) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = account.displayName?.takeIf { it.isNotBlank() } ?: shortHandle(account.userId),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = shortHandle(account.userId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR de mon profil",
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Fais scanner ce code depuis Social, Ajouter un ami.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { shareProfile(context, account) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Partager")
                }
                OutlinedButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Profil Shiro", link))
                        onMessage("Lien copié")
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copier le lien")
                }
            }
        }
    }
}

private fun shareProfile(context: android.content.Context, account: SocialAccount) {
    val link = SocialProfileLink.encode(account.userId, account.displayName)
    val name = account.displayName?.takeIf { it.isNotBlank() } ?: shortHandle(account.userId)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Ajoute-moi sur Shiro ($name) : $link")
    }
    context.startActivity(Intent.createChooser(send, "Partager mon profil Shiro"))
}
