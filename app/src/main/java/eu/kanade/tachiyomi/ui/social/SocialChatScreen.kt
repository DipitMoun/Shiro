package eu.kanade.tachiyomi.ui.social

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import coil3.compose.AsyncImage
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.social.matrix.MomentPayloadCodec
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Work-level chat: one user room per work, one thread per episode.
 *
 * Timecodes are inserted as app-built chips and sent through
 * `org.shiro.moment`. The 30 s preview stays local and is never sent to Matrix.
 */
class SocialChatScreen(
    private val initialContext: SocialChatContext,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        // Opened from a room (Messages, notification): episodes are resolved from the library.
        val chatContext by produceState(initialContext, initialContext) {
            value = eu.kanade.presentation.social.SocialChatContextResolver.enrich(initialContext)
        }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { SocialChatScreenModel() }
        val state by model.state.collectAsStateWithLifecycle()
        val friends by model.friends.collectAsStateWithLifecycle()
        val selfAvatar by model.selfAvatar.collectAsStateWithLifecycle()

        var selectedKey by rememberSaveable(chatContext) {
            mutableStateOf(chatContext.episodes.firstOrNull()?.key?.value)
        }
        val selectedEpisode = chatContext.episodes.firstOrNull { it.key.value == selectedKey }
        val account = state.account
        val pending = remember { mutableStateListOf<SocialChatPending>() }
        val composerChips = remember { mutableStateListOf<Long>() }
        var showTimecodePicker by remember { mutableStateOf(false) }
        var showCommands by remember { mutableStateOf(false) }
        var actionItem by remember { mutableStateOf<SocialTimelineItem?>(null) }
        var replyTarget by remember { mutableStateOf<SocialTimelineItem?>(null) }
        var customReactionTarget by remember { mutableStateOf<SocialTimelineItem?>(null) }
        var customReactionText by remember { mutableStateOf("") }
        var recentReactions by remember(context) {
            mutableStateOf(loadRecentReactions(context).ifEmpty { defaultReactions })
        }
        val participants = remember(friends, account?.userId, account?.displayName, selfAvatar) {
            buildMap {
                friends.forEach { put(it.mxid, it) }
                account?.let {
                    put(
                        it.userId,
                        SocialChatFriend(
                            mxid = it.userId,
                            name = it.displayName ?: "Moi",
                            avatarBytes = selfAvatar,
                        ),
                    )
                }
            }
        }

        LaunchedEffect(
            account?.userId,
            chatContext.work.id,
            chatContext.initialRoomId,
            chatContext.initialAuthorMxid,
        ) {
            val currentAccount = account ?: return@LaunchedEffect
            val initialRoomId = chatContext.initialRoomId
            if (initialRoomId != null) {
                model.openRoomDirect(
                    roomId = initialRoomId,
                    authorMxid = chatContext.initialAuthorMxid ?: currentAccount.userId,
                    context = chatContext,
                )
            } else {
                model.selectWork(
                    context = chatContext,
                    selfMxid = currentAccount.userId,
                    selfDisplayName = currentAccount.displayName
                        ?: currentAccount.userId.substringBefore(':').removePrefix("@"),
                )
            }
        }

        // `state.thread` and `state.timeline` are kept in sync by the controller.
        // They reflect the active author (self by default, a friend after a tap).
        androidx.compose.runtime.DisposableEffect(state.activeRoomId) {
            eu.kanade.tachiyomi.ui.social.SocialNotificationState.activeRoomId = state.activeRoomId
            onDispose {
                eu.kanade.tachiyomi.ui.social.SocialNotificationState.activeRoomId = null
            }
        }

        val timeline = if (state.activeRoomId != null) state.roomTimeline else state.timeline
        val items = timeline?.items.orEmpty()

        LaunchedEffect(state.thread, state.activeRoomId) {
            pending.clear()
            replyTarget = null
            actionItem = null
            customReactionTarget = null
        }

        LaunchedEffect(state.lastError) {
            if (state.lastError != null) pending.clear()
        }

        LaunchedEffect(items, pending.size) {
            if (pending.isEmpty()) return@LaunchedEffect
            val sentTexts = items.mapNotNull { item ->
                when (item) {
                    is SocialTimelineItem.MomentItem -> item.payload.note
                    is SocialTimelineItem.ReplyItem -> item.body
                }
            }.toSet()
            pending.removeAll { it.text in sentTexts }
        }

        Box(Modifier.fillMaxSize()) {
            ChatBackground(posterUrl = chatContext.posterUrl)
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (chatContext.posterUrl != null) {
                                    AsyncImage(
                                        model = chatContext.posterUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                                shape = CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column {
                                    Text(
                                        text = chatContext.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val activeAuthor = state.activeAuthorMxid ?: state.thread?.authorMxid
                                    val selfMxid = account?.userId
                                    if (
                                        activeAuthor != null &&
                                        selfMxid != null &&
                                        activeAuthor != selfMxid
                                    ) {
                                        val label = participants[activeAuthor]?.name
                                            ?: activeAuthor.substringBefore(':').removePrefix("@")
                                        Text(
                                            text = "Salon de $label",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = navigator::pop) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                    )
                },
            ) { contentPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .imePadding(),
                ) {
                FriendsRail(
                    self = account?.let { participants[it.userId] },
                    friends = friends,
                    activeMxid = state.activeAuthorMxid ?: state.thread?.authorMxid,
                    onSelect = { participant ->
                        model.selectSalon(participant, chatContext)
                    },
                )

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (account == null) {
                        ChatStateBody(
                            title = "Compte Matrix requis",
                            subtitle = "Connecte-toi depuis Paramètres → Social.",
                        )
                    } else if (selectedEpisode == null) {
                        ChatStateBody(
                            title = "Aucun épisode",
                            subtitle = "Cette œuvre n'a pas encore d'épisode.",
                        )
                    } else {
                        val messageById = remember(items) { items.associateBy { it.eventId } }
                        val listState = rememberLazyListState()
                        LaunchedEffect(items.size, pending.size) {
                            val count = items.size + pending.size
                            if (count > 0) listState.animateScrollToItem(count - 1)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            itemsIndexed(items, key = { _, item -> item.eventId }) { index, item ->
                                val previous = items.getOrNull(index - 1)
                                val showHeader = previous == null ||
                                    previous.sender != item.sender ||
                                    Duration.between(previous.sentAt, item.sentAt).toMinutes() >= 5
                                ChatMessage(
                                    item = item,
                                    selfMxid = account.userId,
                                    participants = participants,
                                    messageById = messageById,
                                    currentEpisode = selectedEpisode,
                                    episodes = chatContext.episodes,
                                    posterUrl = chatContext.posterUrl,
                                    showHeader = showHeader,
                                    onPreview = { episode, positionMs, loop ->
                                        openPreview(context, chatContext, episode, positionMs, loop)
                                    },
                                    onLongPress = { actionItem = item },
                                    onReaction = { key -> model.react(item.eventId, key) },
                                )
                            }
                            items(pending, key = { it.id }) { message ->
                                ChatPendingMessage(
                                    text = message.text,
                                    positions = message.positions,
                                    participant = participants[account.userId]
                                        ?: SocialChatFriend(account.userId, "Moi", null),
                                    episode = selectedEpisode,
                                    posterUrl = chatContext.posterUrl,
                                    onPreview = { episode, positionMs, loop ->
                                        openPreview(context, chatContext, episode, positionMs, loop)
                                    },
                                )
                            }
                        }

                        state.lastError?.let { error ->
                            Text(
                                text = "Matrix : $error",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }

                        replyTarget?.let { target ->
                            ReplyComposerBanner(
                                target = target,
                                participants = participants,
                                onDismiss = { replyTarget = null },
                            )
                        }

                        ChatComposer(
                            chips = composerChips,
                            onRemoveChip = { composerChips.remove(it) },
                            onCommands = { showCommands = true },
                            onTimecodeRequest = { showTimecodePicker = true },
                            onSend = { text ->
                                val body = text.trim()
                                val positions = composerChips.toList()
                                if (body.isNotEmpty() || positions.isNotEmpty()) {
                                    pending += SocialChatPending(
                                        id = "pending-${pending.size}-${System.nanoTime()}",
                                        text = body,
                                        positions = positions,
                                    )
                                    model.send(
                                        text = body,
                                        positions = positions,
                                        episode = selectedEpisode,
                                        context = chatContext,
                                        inReplyToEventId = replyTarget?.eventId,
                                    )
                                    composerChips.clear()
                                    replyTarget = null
                                }
                            },
                        )
                    }
                }
            }
        }
        }

        if (showTimecodePicker && selectedEpisode != null) {
            TimecodePickerDialog(
                animeTitle = chatContext.title,
                episodes = chatContext.episodes,
                episode = selectedEpisode,
                onDismiss = { showTimecodePicker = false },
                onPick = { momentEpisode, positionMs ->
                    selectedKey = momentEpisode.key.value
                    if (composerChips.size < 5) composerChips += positionMs
                    showTimecodePicker = false
                },
            )
        }

        if (showCommands && selectedEpisode != null) {
            ModalBottomSheet(
                onDismissRequest = { showCommands = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Commandes",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${chatContext.title} · ${selectedEpisode.key.value}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showCommands = false
                                showTimecodePicker = true
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Timecode", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Insérer un extrait de l'épisode",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        actionItem?.let { item ->
            ModalBottomSheet(
                onDismissRequest = { actionItem = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = messageText(item).replace('\n', ' ').take(120),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        recentReactions.take(6).forEach { key ->
                            Text(
                                text = key,
                                fontSize = 25.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        model.react(item.eventId, key)
                                        recentReactions = rememberRecentReaction(context, key)
                                        actionItem = null
                                    }
                                    .padding(8.dp),
                            )
                        }
                        Text(
                            text = "+",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    customReactionTarget = item
                                    customReactionText = ""
                                    actionItem = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clickable {
                                replyTarget = item
                                actionItem = null
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(
                                    text = "Répondre",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Citer ce message dans ta réponse",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                }
            }
        }

        customReactionTarget?.let { target ->
            AlertDialog(
                onDismissRequest = {
                    customReactionTarget = null
                    customReactionText = ""
                },
                title = { Text("Ajouter une réaction") },
                text = {
                    OutlinedTextField(
                        value = customReactionText,
                        onValueChange = { customReactionText = it.take(16) },
                        label = { Text("Emoji ou texte") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = customReactionText.isNotBlank(),
                        onClick = {
                            val key = customReactionText.trim()
                            if (key.isNotEmpty()) {
                                model.react(target.eventId, key)
                                recentReactions = rememberRecentReaction(context, key)
                                customReactionTarget = null
                                customReactionText = ""
                            }
                        },
                    ) {
                        Text("Ajouter")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            customReactionTarget = null
                            customReactionText = ""
                        },
                    ) {
                        Text("Annuler")
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatBackground(posterUrl: String?) {
    Box(Modifier.fillMaxSize()) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(38.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.68f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun FriendsRail(
    self: SocialChatFriend?,
    friends: List<SocialChatFriend>,
    activeMxid: String?,
    onSelect: (SocialChatFriend) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        self?.let { participant ->
            item(key = "self") {
                FriendRailAvatar(
                    participant = participant,
                    isActive = participant.mxid == activeMxid,
                    onSelect = onSelect,
                )
            }
        }
        items(
            friends.filter { it.mxid != self?.mxid },
            key = { it.mxid },
        ) { friend ->
            FriendRailAvatar(
                participant = friend,
                isActive = friend.mxid == activeMxid,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun FriendRailAvatar(
    participant: SocialChatFriend,
    isActive: Boolean,
    onSelect: (SocialChatFriend) -> Unit,
) {
    ParticipantAvatar(
        participant = participant,
        size = 38.dp,
        isActive = isActive,
        enabled = participant.hasRoom,
        onClick = if (!isActive && participant.hasRoom) {
            { onSelect(participant) }
        } else {
            null
        },
    )
}

@Composable
private fun EpisodeRail(
    episodes: List<SocialChatEpisode>,
    selectedKey: String?,
    onSelect: (SocialChatEpisode) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        items(episodes, key = { it.key.value }) { episode ->
            val selected = episode.key.value == selectedKey
            val label = if (episode.number % 1.0 == 0.0) {
                "Ep ${episode.number.toInt()}"
            } else {
                "Ep ${episode.number}"
            }
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                    .clickable { onSelect(episode) },
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 9.dp),
                )
            }
        }
    }
}

private val chatTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private val defaultReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
private const val REACTION_PREFS = "shiro_social_reactions"
private const val REACTION_KEY = "recent"
private const val REACTION_SEPARATOR = "\u001F"

private fun loadRecentReactions(context: Context): List<String> {
    val raw = context.getSharedPreferences(REACTION_PREFS, Context.MODE_PRIVATE)
        .getString(REACTION_KEY, "")
        .orEmpty()
    return raw.split(REACTION_SEPARATOR).filter { it.isNotBlank() }
}

private fun rememberRecentReaction(context: Context, key: String): List<String> {
    val next = (listOf(key) + loadRecentReactions(context) + defaultReactions)
        .distinct()
        .take(18)
    context.getSharedPreferences(REACTION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(REACTION_KEY, next.joinToString(REACTION_SEPARATOR))
        .apply()
    return next
}

private val participantColors = listOf(
    Color(0xFF89B4FA),
    Color(0xFFF9A875),
    Color(0xFFB4BEFE),
    Color(0xFFA6E3A1),
    Color(0xFFF38BA8),
    Color(0xFFF5C2E7),
)

private fun participantColor(mxid: String): Color =
    participantColors[(mxid.hashCode() and Int.MAX_VALUE) % participantColors.size]

private fun fallbackParticipant(mxid: String): SocialChatFriend =
    SocialChatFriend(
        mxid = mxid,
        name = mxid.substringBefore(':').removePrefix("@"),
        avatarBytes = null,
    )

private fun messageText(item: SocialTimelineItem): String = when (item) {
    is SocialTimelineItem.MomentItem ->
        item.payload.note ?: MomentPayloadCodec.fallbackBody(item.payload)
    is SocialTimelineItem.ReplyItem -> item.body
}

@Composable
private fun ChatMessage(
    item: SocialTimelineItem,
    selfMxid: String,
    participants: Map<String, SocialChatFriend>,
    messageById: Map<String, SocialTimelineItem>,
    currentEpisode: SocialChatEpisode,
    episodes: List<SocialChatEpisode>,
    posterUrl: String?,
    showHeader: Boolean,
    onPreview: (SocialChatEpisode, Long, Boolean) -> Unit,
    onLongPress: (SocialTimelineItem) -> Unit,
    onReaction: (String) -> Unit,
) {
    val participant = participants[item.sender] ?: fallbackParticipant(item.sender)
    val momentEpisode = when (item) {
        is SocialTimelineItem.MomentItem -> {
            episodes.firstOrNull { it.key.value == item.payload.episode.key } ?: currentEpisode
        }
        is SocialTimelineItem.ReplyItem -> currentEpisode
    }
    val positions = when (item) {
        is SocialTimelineItem.MomentItem -> item.payload.moments.map { it.positionMs }
        is SocialTimelineItem.ReplyItem -> emptyList()
    }
    val replyTarget = (item as? SocialTimelineItem.ReplyItem)
        ?.inReplyToEventId
        ?.let(messageById::get)

    DiscordMessageRow(
        participant = participant,
        text = messageText(item),
        timestamp = item.sentAt,
        replyTarget = replyTarget,
        participants = participants,
        reactions = item.reactions.map { it.key to it.count },
        positions = positions,
        episode = momentEpisode,
        posterUrl = posterUrl,
        isMine = item.sender == selfMxid,
        showHeader = showHeader,
        onPreview = onPreview,
        onLongPress = { onLongPress(item) },
        onReaction = onReaction,
    )
}

@Composable
private fun ChatPendingMessage(
    text: String,
    positions: List<Long>,
    participant: SocialChatFriend,
    episode: SocialChatEpisode,
    posterUrl: String?,
    onPreview: (SocialChatEpisode, Long, Boolean) -> Unit,
) {
    DiscordMessageRow(
        participant = participant,
        text = text,
        timestamp = null,
        replyTarget = null,
        participants = emptyMap(),
        reactions = emptyList(),
        positions = positions,
        episode = episode,
        posterUrl = posterUrl,
        isMine = true,
        pending = true,
        onPreview = onPreview,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscordMessageRow(
    participant: SocialChatFriend,
    text: String,
    timestamp: Instant?,
    replyTarget: SocialTimelineItem?,
    participants: Map<String, SocialChatFriend>,
    reactions: List<Pair<String, Int>>,
    positions: List<Long>,
    episode: SocialChatEpisode,
    posterUrl: String?,
    isMine: Boolean,
    pending: Boolean = false,
    showHeader: Boolean = true,
    onPreview: (SocialChatEpisode, Long, Boolean) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onReaction: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        if (showHeader) {
            ParticipantAvatar(
                participant = participant,
                size = 40.dp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(9.dp))
        } else {
            Spacer(Modifier.width(49.dp))
        }
        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = participant.name,
                        color = participantColor(participant.mxid),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isMine) {
                        Text(
                            text = " · toi",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    timestamp?.let {
                        Text(
                            text = chatTimeFormatter.format(it),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            replyTarget?.let { target ->
                ReplyQuote(
                    target = target,
                    participants = participants,
                )
            }

            if (text.isNotBlank()) {
                Text(
                    text = text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    reactions.take(6).forEach { (key, count) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(7.dp),
                            modifier = Modifier.clickable { onReaction?.invoke(key) },
                        ) {
                            Text(
                                text = "$key $count",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            positions.forEach { position ->
                ChatPreviewCard(
                    positionMs = position,
                    episode = episode,
                    posterUrl = posterUrl,
                    onPreview = onPreview,
                )
            }

            if (pending) {
                Text(
                    text = "Envoi…",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplyQuote(
    target: SocialTimelineItem,
    participants: Map<String, SocialChatFriend>,
) {
    val participant = participants[target.sender] ?: fallbackParticipant(target.sender)
    val snippet = messageText(target).replace('\n', ' ').take(120)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParticipantAvatar(participant = participant, size = 18.dp)
        Text(
            text = " ${participant.name}  ",
            fontSize = 10.sp,
            color = participantColor(participant.mxid),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = snippet,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ParticipantAvatar(
    participant: SocialChatFriend,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = remember(participant.avatarBytes) {
        participant.avatarBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    // Active + clickable avatars only appear in the top-bar row.
    // Message avatars (onClick == null, isActive == false) keep their previous look.
    val baseModifier = modifier
        .size(size)
        .alpha(if (enabled) 1f else 0.35f)
        .clip(CircleShape)
        .then(
            when {
                isActive -> Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                )
                onClick != null -> Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                )
                else -> Modifier
            },
        )
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = participant.name,
            contentScale = ContentScale.Crop,
            modifier = baseModifier,
        )
    } else {
        Box(
            modifier = baseModifier.background(participantColor(participant.mxid).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = participant.name
                    .split(' ', '-', '_')
                    .mapNotNull { it.firstOrNull() }
                    .take(2)
                    .joinToString("")
                    .uppercase()
                    .ifEmpty { "?" },
                fontSize = (size.value * 0.32f).sp,
                fontWeight = FontWeight.SemiBold,
                color = participantColor(participant.mxid),
            )
        }
    }
}

@Composable
private fun PreviewActionButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.58f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ChatPreviewCard(
    positionMs: Long,
    episode: SocialChatEpisode,
    posterUrl: String?,
    onPreview: (SocialChatEpisode, Long, Boolean) -> Unit,
) {
    var revealed by rememberSaveable(episode.key.value, positionMs) { mutableStateOf(false) }
    val durationMs = episode.durationSeconds.takeIf { it > 0L }?.times(1000L)
    val start = (positionMs - 15_000L).coerceAtLeast(0L)
    val end = durationMs?.let { (positionMs + 15_000L).coerceAtMost(it) }
        ?: (positionMs + 15_000L)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .widthIn(max = 320.dp)
            .padding(top = 7.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clickable { if (!revealed) revealed = true },
                contentAlignment = Alignment.Center,
            ) {
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(if (revealed) 0.dp else 22.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                            ),
                        ),
                )
                if (!revealed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White)
                        Text(
                            text = "Spoiler",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Toucher pour révéler",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 9.sp,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PreviewActionButton(
                            label = "Boucler 30 s",
                            onClick = { onPreview(episode, positionMs, true) },
                        )
                        PreviewActionButton(
                            label = "Reprendre",
                            onClick = { onPreview(episode, positionMs, false) },
                        )
                    }
                }
                Text(
                    text = "${episode.key.value} · ${formatTimecode(start)} – ${formatTimecode(end)}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplyComposerBanner(
    target: SocialTimelineItem,
    participants: Map<String, SocialChatFriend>,
    onDismiss: () -> Unit,
) {
    val participant = participants[target.sender] ?: fallbackParticipant(target.sender)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParticipantAvatar(participant = participant, size = 20.dp)
        Column(Modifier.weight(1f).padding(start = 7.dp)) {
            Text(
                text = participant.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = participantColor(participant.mxid),
            )
            Text(
                text = messageText(target).replace('\n', ' '),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Annuler la réponse", modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun ChatComposer(
    chips: List<Long>,
    onRemoveChip: (Long) -> Unit,
    onCommands: () -> Unit,
    onTimecodeRequest: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (chips.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, bottom = 6.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                chips.forEach { position ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatTimecode(position),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                            )
                            IconButton(
                                onClick = { onRemoveChip(position) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCommands) {
                    Icon(Icons.Default.Add, contentDescription = "Commandes")
                }
                Box(Modifier.weight(1f)) {
                    if (draft.isEmpty()) {
                        Text(
                            text = "Envoyer un message…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { value ->
                            if (value.contains("/timecode", ignoreCase = true)) {
                                draft = value.replace("/timecode", "", ignoreCase = true)
                                onTimecodeRequest()
                            } else {
                                draft = value
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        maxLines = 4,
                    )
                }
                IconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() || chips.isNotEmpty(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Envoyer",
                        tint = if (draft.isNotBlank() || chips.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimecodePickerDialog(
    animeTitle: String,
    episodes: List<SocialChatEpisode>,
    episode: SocialChatEpisode,
    onDismiss: () -> Unit,
    onPick: (SocialChatEpisode, Long) -> Unit,
) {
    var selectedKey by remember(episode.key.value) { mutableStateOf(episode.key.value) }
    val chosenEpisode = episodes.firstOrNull { it.key.value == selectedKey } ?: episode
    val maxMinutes = (chosenEpisode.durationSeconds / 60L).toInt().coerceAtLeast(0)
    var minute by remember(chosenEpisode.key.value) { mutableIntStateOf(0) }
    var seconds by remember(chosenEpisode.key.value) { mutableIntStateOf(0) }
    val maxSecondsForMinute = if (
        chosenEpisode.durationSeconds > 0L &&
        minute == maxMinutes
    ) {
        (chosenEpisode.durationSeconds % 60L).toInt()
    } else {
        59
    }
    val effectiveSeconds = seconds.coerceAtMost(maxSecondsForMinute)
    val pickedSeconds = (minute * 60L + effectiveSeconds).let { total ->
        if (chosenEpisode.durationSeconds > 0L) {
            total.coerceAtMost(chosenEpisode.durationSeconds)
        } else {
            total
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(animeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${chosenEpisode.key.value} · ${formatTimecode(chosenEpisode.durationSeconds * 1000L)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    episodes.forEach { candidate ->
                        Surface(
                            color = if (candidate.key == chosenEpisode.key) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            shape = RoundedCornerShape(7.dp),
                            modifier = Modifier.clickable {
                                selectedKey = candidate.key.value
                                minute = 0
                                seconds = 0
                            },
                        ) {
                            Text(
                                text = candidate.key.value,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Text("Choisis la minute", fontSize = 11.sp)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items((0..maxMinutes).toList()) { value ->
                        Surface(
                            color = if (value == minute) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                            shape = RoundedCornerShape(7.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    minute = value
                                    val max = if (
                                        chosenEpisode.durationSeconds > 0L &&
                                        value == maxMinutes
                                    ) {
                                        (chosenEpisode.durationSeconds % 60L).toInt()
                                    } else {
                                        59
                                    }
                                    seconds = seconds.coerceAtMost(max)
                                },
                        ) {
                            Text(
                                text = "$value min",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                Text("Secondes : $effectiveSeconds", fontSize = 11.sp)
                Slider(
                    value = effectiveSeconds.toFloat(),
                    onValueChange = { seconds = it.toInt().coerceIn(0, maxSecondsForMinute) },
                    valueRange = 0f..maxSecondsForMinute.toFloat(),
                    steps = (maxSecondsForMinute - 1).coerceAtLeast(0),
                )
                Text(
                    text = "Timecode : ${formatTimecode(pickedSeconds * 1000L)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPick(chosenEpisode, pickedSeconds * 1000L) }) {
                Text("Insérer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

@Composable
private fun ChatStateBody(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openPreview(
    context: android.content.Context,
    chatContext: SocialChatContext,
    episode: SocialChatEpisode,
    positionMs: Long,
    loop: Boolean,
) {
    context.startActivity(
        PlayerActivity.newIntent(
            context = context,
            animeId = chatContext.animeId,
            episodeId = episode.id,
            previewPositionMs = positionMs,
            previewLoop = loop,
        ),
    )
}

private data class SocialChatPending(
    val id: String,
    val text: String,
    val positions: List<Long>,
)
