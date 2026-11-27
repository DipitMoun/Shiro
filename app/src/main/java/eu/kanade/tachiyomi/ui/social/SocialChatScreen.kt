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
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.model.asMangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf as chatMutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.draw.scale
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
        val typingIds by model.typing.collectAsStateWithLifecycle()
        // Same cover model as the work page (cover cache, source headers), not the raw URL:
        // some covers only load through it.
        val coverModel by produceState<Any?>(
            chatContext.posterUrl,
            chatContext.animeId,
            chatContext.medium,
            chatContext.posterUrl,
        ) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    if (chatContext.animeId <= 0L) {
                        null
                    } else if (chatContext.medium == SocialChatMedium.MANGA) {
                        Injekt.get<GetManga>().await(chatContext.animeId)?.asMangaCover()
                    } else {
                        Injekt.get<GetAnime>().await(chatContext.animeId)?.asAnimeCover()
                    }
                }.getOrNull()
            } ?: chatContext.posterUrl
        }
        val clipboard = LocalClipboardManager.current

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

        val imageScope = androidx.compose.runtime.rememberCoroutineScope()
        val sendImageNow: (PickedImage, String?) -> Unit = { image, replyTo ->
            val ownImages = items.count {
                it is SocialTimelineItem.ReplyItem && it.media != null && it.sender == account?.userId
            }
            pending += SocialChatPending(
                id = "pending-image-${System.nanoTime()}",
                text = "",
                positions = emptyList(),
                inReplyToEventId = replyTo,
                image = image,
                imageBaseline = ownImages,
            )
            model.sendImage(image, "", replyTo)
        }
        val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                imageScope.launch {
                    val image = ChatImagePreparer.prepare(context, uri)
                    if (image == null) {
                        android.widget.Toast
                            .makeText(context, "Image illisible ou trop lourde", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        sendImageNow(image, replyTarget?.eventId)
                        replyTarget = null
                    }
                }
            }
        }
        val launchImagePicker: () -> Unit = {
            pickImage.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }

        LaunchedEffect(state.thread, state.activeRoomId) {
            pending.clear()
            replyTarget = null
            actionItem = null
            customReactionTarget = null
        }

        // A failed send stays in the list, marked, so it can be retried with one tap.
        LaunchedEffect(state.lastError) {
            if (state.lastError != null) {
                for (i in pending.indices) pending[i] = pending[i].copy(failed = true)
            }
        }

        LaunchedEffect(items, pending.size) {
            if (pending.isEmpty()) return@LaunchedEffect
            val sentTexts = items.mapNotNull { item ->
                when (item) {
                    is SocialTimelineItem.MomentItem -> item.payload.note
                    is SocialTimelineItem.ReplyItem -> item.body
                }
            }.toSet()
            val ownImages = items.count {
                it is SocialTimelineItem.ReplyItem && it.media != null && it.sender == account?.userId
            }
            pending.removeAll { it.text in sentTexts && it.image == null }
            pending.removeAll { it.image != null && ownImages > it.imageBaseline }
        }

        Box(Modifier.fillMaxSize()) {
            ChatBackground(posterUrl = coverModel)
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (coverModel != null) {
                                    AsyncImage(
                                        model = coverModel,
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
                                    if (typingIds.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = typingLabel(
                                                    typingIds.map { participants[it]?.name ?: fallbackParticipant(it).name },
                                                ),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            TypingDots(MaterialTheme.colorScheme.primary)
                                        }
                                    } else if (
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
                            title = if (chatContext.medium == SocialChatMedium.MANGA) "Aucun chapitre" else "Aucun épisode",
                            subtitle = if (chatContext.medium == SocialChatMedium.MANGA) {
                                "Cette œuvre n'a pas encore de chapitre."
                            } else {
                                "Cette œuvre n'a pas encore d'épisode."
                            },
                        )
                    } else {
                        val messageById = remember(items) { items.associateBy { it.eventId } }
                        val roomId = timeline?.roomId
                        val selfMxid = account.userId
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

                        // Newest message already seen when the chat was last left: marks what is new.
                        val unseenAfter = remember(roomId) { roomId?.let { loadSeenEvent(context, it) } }
                        val rows = remember(items, participants, selfMxid, unseenAfter) {
                            buildChatRows(items, selfMxid, participants, unseenAfter)
                        }
                        val rowIndexById = remember(rows) { rows.withIndex().associate { it.value.key to it.index } }
                        val total = rows.size + pending.size

                        var previousTotal by remember(roomId) { chatMutableIntStateOf(0) }
                        LaunchedEffect(total, roomId) {
                            if (total == 0) return@LaunchedEffect
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            val newestIsMine = pending.isNotEmpty() ||
                                (rows.lastOrNull() as? ChatRow.Message)?.item?.sender == selfMxid
                            when {
                                previousTotal == 0 -> {
                                    val marker = rows.indexOfFirst { it is ChatRow.NewMarker }
                                    listState.scrollToItem(if (marker >= 0) marker else total - 1)
                                }
                                lastVisible >= previousTotal - 2 || newestIsMine ->
                                    listState.animateScrollToItem(total - 1)
                            }
                            previousTotal = total
                        }
                        val atBottom by remember {
                            derivedStateOf {
                                val info = listState.layoutInfo
                                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
                            }
                        }

                        // Read receipt: tell the room we have read up to the newest message, only
                        // while the screen is really in front.
                        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
                        val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
                        val newestEventId = items.lastOrNull()?.eventId
                        LaunchedEffect(roomId, newestEventId, resumed) {
                            if (roomId != null && newestEventId != null && resumed) model.markRead(roomId, newestEventId)
                        }
                        LaunchedEffect(roomId) {
                            if (roomId != null) model.watchTyping(roomId, selfMxid)
                        }
                        val latestSeen by rememberUpdatedState(newestEventId)
                        androidx.compose.runtime.DisposableEffect(roomId) {
                            onDispose { if (roomId != null) latestSeen?.let { saveSeenEvent(context, roomId, it) } }
                        }

                        val sendNow: (String, List<Long>, String?) -> Unit = { body, positions, replyTo ->
                            pending += SocialChatPending(
                                id = "pending-${pending.size}-${System.nanoTime()}",
                                text = body,
                                positions = positions,
                                inReplyToEventId = replyTo,
                            )
                            model.send(
                                text = body,
                                positions = positions,
                                episode = selectedEpisode,
                                context = chatContext,
                                inReplyToEventId = replyTo,
                            )
                        }

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 10.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(rows, key = { it.key }) { row ->
                                    when (row) {
                                        is ChatRow.Day -> ChatDayDivider(row.date)
                                        ChatRow.NewMarker -> ChatNewMarker()
                                        is ChatRow.Message -> {
                                            val item = row.item
                                            val momentEpisode = when (item) {
                                                is SocialTimelineItem.MomentItem ->
                                                    chatContext.episodes.firstOrNull { it.key.value == item.payload.episode.key }
                                                        ?: selectedEpisode
                                                is SocialTimelineItem.ReplyItem -> selectedEpisode
                                            }
                                            val moments = when (item) {
                                                is SocialTimelineItem.MomentItem -> item.payload.moments.map {
                                                    ChatMomentModel(momentEpisode, it.page?.toLong() ?: it.positionMs)
                                                }
                                                is SocialTimelineItem.ReplyItem -> emptyList()
                                            }
                                            val replyTo = (item as? SocialTimelineItem.ReplyItem)
                                                ?.inReplyToEventId
                                                ?.let(messageById::get)
                                            Box(Modifier.padding(top = if (row.first) 6.dp else 0.dp)) {
                                                ChatBubble(
                                                    participant = participants[item.sender] ?: fallbackParticipant(item.sender),
                                                    content = ChatBubbleContent(
                                                        text = when (item) {
                                                            is SocialTimelineItem.MomentItem -> item.payload.note.orEmpty()
                                                            is SocialTimelineItem.ReplyItem -> item.body
                                                        },
                                                        moments = moments,
                                                        replyTarget = replyTo,
                                                        image = (item as? SocialTimelineItem.ReplyItem)?.media,
                                                    ),
                                                    timestamp = item.sentAt,
                                                    mine = item.sender == selfMxid,
                                                    first = row.first,
                                                    last = row.last,
                                                    medium = chatContext.medium,
                                                    posterUrl = coverModel,
                                                    participants = participants,
                                                    selfMxid = selfMxid,
                                                    reactions = item.reactions,
                                                    readers = row.readers,
                                                    seen = row.seen,
                                                    state = item.deliveryState,
                                                    onOpenMoment = { episode, positionMs, loop ->
                                                        openPreview(context, chatContext, episode, positionMs, loop)
                                                    },
                                                    onJumpTo = { id ->
                                                        rowIndexById[id]?.let { index ->
                                                            scope.launch { listState.animateScrollToItem(index) }
                                                        }
                                                    },
                                                    onReply = { replyTarget = item },
                                                    onLongPress = { actionItem = item },
                                                    onReaction = { key -> model.react(item.eventId, key) },
                                                )
                                            }
                                        }
                                    }
                                }
                                items(pending, key = { it.id }) { message ->
                                    // No answer from the server after a while: offer a retry.
                                    LaunchedEffect(message.id) {
                                        kotlinx.coroutines.delay(20_000)
                                        val index = pending.indexOfFirst { it.id == message.id }
                                        if (index >= 0 && !pending[index].failed) pending[index] = pending[index].copy(failed = true)
                                    }
                                    Box(Modifier.padding(top = 6.dp)) {
                                        ChatBubble(
                                            participant = participants[selfMxid] ?: SocialChatFriend(selfMxid, "Moi", null),
                                            content = ChatBubbleContent(
                                                text = message.text,
                                                moments = message.positions.map { ChatMomentModel(selectedEpisode, it) },
                                                replyTarget = null,
                                                pendingImage = message.image,
                                            ),
                                            timestamp = null,
                                            mine = true,
                                            first = true,
                                            last = true,
                                            medium = chatContext.medium,
                                            posterUrl = coverModel,
                                            participants = participants,
                                            selfMxid = selfMxid,
                                            reactions = emptyList(),
                                            readers = emptyList(),
                                            seen = false,
                                            state = if (message.failed) {
                                                eu.kanade.tachiyomi.data.social.matrix.DeliveryState.FAILED
                                            } else {
                                                eu.kanade.tachiyomi.data.social.matrix.DeliveryState.PENDING
                                            },
                                            onOpenMoment = { episode, positionMs, loop ->
                                                openPreview(context, chatContext, episode, positionMs, loop)
                                            },
                                            onJumpTo = {},
                                            onReply = {},
                                            onLongPress = null,
                                            onReaction = null,
                                            onRetry = {
                                                pending.remove(message)
                                                if (message.image != null) {
                                                    sendImageNow(message.image, message.inReplyToEventId)
                                                } else {
                                                    sendNow(message.text, message.positions, message.inReplyToEventId)
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            val jumpVisible = !atBottom && total > 0
                            val jumpAlpha by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (jumpVisible) 1f else 0f,
                                label = "jump",
                            )
                            if (jumpAlpha > 0.01f) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    shape = CircleShape,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(14.dp)
                                        .size(40.dp)
                                        .alpha(jumpAlpha)
                                        .scale(0.7f + 0.3f * jumpAlpha)
                                        .clip(CircleShape)
                                        .clickable {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            scope.launch { listState.animateScrollToItem((total - 1).coerceAtLeast(0)) }
                                        },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Aller au dernier message")
                                    }
                                }
                            }
                        }

                        if (typingIds.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TypingDots(MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = typingLabel(
                                        typingIds.map { participants[it]?.name ?: fallbackParticipant(it).name },
                                    ),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        state.lastError?.let { error ->
                            Text(
                                text = if (error is eu.kanade.tachiyomi.data.social.matrix.SocialError.Network) {
                                    "Connexion perdue : le message n'a pas été envoyé. Réessaie."
                                } else {
                                    "Matrix : $error"
                                },
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

                        // Opened from a room (Messages, notification) for a work the user does not have:
                        // replying needs the work, so the user is invited to add it instead.
                        val notInLibrary = chatContext.initialRoomId != null && chatContext.animeId == 0L
                        val openScope = androidx.compose.runtime.rememberCoroutineScope()
                        var opening by remember { mutableStateOf(false) }
                        var missingExtension by remember { mutableStateOf<eu.kanade.tachiyomi.data.social.WorkSourceRef?>(null) }
                        var installingExtension by remember { mutableStateOf(false) }
                        val searchByTitle: () -> Unit = {
                            opening = true
                            openScope.launch {
                                try {
                                    val screen = eu.kanade.presentation.social.SocialWorkOpener()
                                        .open(chatContext.title, isManga = if (chatContext.medium == SocialChatMedium.MANGA) true else null)
                                    if (screen != null) {
                                        navigator.push(screen)
                                    } else {
                                        android.widget.Toast
                                            .makeText(context, "Œuvre introuvable", android.widget.Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                } finally {
                                    opening = false
                                }
                            }
                        }
                        missingExtension?.let { ref ->
                            eu.kanade.presentation.social.MissingExtensionDialog(
                                ref = ref,
                                installing = installingExtension,
                                onInstall = {
                                    installingExtension = true
                                    openScope.launch {
                                        val started = runCatching {
                                            eu.kanade.tachiyomi.data.social.ExtensionInstallHelper.install(ref)
                                        }.getOrDefault(false)
                                        installingExtension = false
                                        missingExtension = null
                                        android.widget.Toast.makeText(
                                            context,
                                            if (started) {
                                                "Installation de l'extension lancée, rouvre l'œuvre ensuite"
                                            } else {
                                                "Extension introuvable dans tes dépôts : ajoute le bon dépôt d'abord"
                                            },
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                onSearchInstead = {
                                    missingExtension = null
                                    searchByTitle()
                                },
                                onDismiss = { missingExtension = null },
                            )
                        }
                        if (notInLibrary) {
                            AddToLibraryPrompt(
                                title = chatContext.title,
                                loading = opening,
                                onOpen = {
                                    if (!opening) {
                                        opening = true
                                        openScope.launch {
                                            // The owner's extension and source first: the exact version, no search.
                                            val ref = chatContext.initialRoomId?.let {
                                                runCatching { Injekt.get<eu.kanade.tachiyomi.data.social.matrix.room.RoomSourceReader>().read(it) }.getOrNull()
                                            }
                                            val exact = ref?.let { eu.kanade.presentation.social.SocialWorkOpener().openBySource(it) }
                                            opening = false
                                            when (exact) {
                                                is eu.kanade.presentation.social.SourceOpenResult.Open -> navigator.push(exact.screen)
                                                is eu.kanade.presentation.social.SourceOpenResult.MissingExtension ->
                                                    missingExtension = exact.ref
                                                null -> searchByTitle()
                                            }
                                        }
                                    }
                                },
                            )
                        } else ChatComposer(
                            draftKey = "${roomId ?: chatContext.work.id.value}",
                            chips = composerChips,
                            medium = chatContext.medium,
                            onRemoveChip = { composerChips.remove(it) },
                            onCommands = { showCommands = true },
                            onTimecodeRequest = { showTimecodePicker = true },
                            onTyping = { if (roomId != null) model.notifyTyping(roomId, true) },
                            onAttach = launchImagePicker,
                            onSend = { text ->
                                val body = text.trim()
                                val positions = composerChips.toList()
                                if (body.isNotEmpty() || positions.isNotEmpty()) {
                                    sendNow(body, positions, replyTarget?.eventId)
                                    if (roomId != null) model.notifyTyping(roomId, false)
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
            if (chatContext.medium == SocialChatMedium.MANGA) {
                PagePickerDialog(
                    mangaTitle = chatContext.title,
                    chapters = chatContext.episodes,
                    chapter = selectedEpisode,
                    onDismiss = { showTimecodePicker = false },
                    onPick = { momentChapter, page ->
                        selectedKey = momentChapter.key.value
                        if (composerChips.size < 5) composerChips += page.toLong()
                        showTimecodePicker = false
                    },
                )
            } else {
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
                                launchImagePicker()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Image ou GIF", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Envoyer une photo ou un GIF de ta galerie",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                        val isManga = chatContext.medium == SocialChatMedium.MANGA
                        Icon(
                            imageVector = if (isManga) {
                                Icons.AutoMirrored.Outlined.MenuBook
                            } else {
                                Icons.Outlined.AccessTime
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                if (isManga) "Page" else "Timecode",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (isManga) "Partager une page du chapitre" else "Insérer un extrait de l'épisode",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(messageText(item)))
                                actionItem = null
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Copier le texte",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 10.dp),
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
private fun ChatBackground(posterUrl: Any?) {
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

internal val chatTimeFormatter: DateTimeFormatter =
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

internal fun participantColor(mxid: String): Color =
    participantColors[(mxid.hashCode() and Int.MAX_VALUE) % participantColors.size]

internal fun fallbackParticipant(mxid: String): SocialChatFriend =
    SocialChatFriend(
        mxid = mxid,
        name = mxid.substringBefore(':').removePrefix("@"),
        avatarBytes = null,
    )

internal fun messageText(item: SocialTimelineItem): String = when (item) {
    is SocialTimelineItem.MomentItem ->
        item.payload.note ?: MomentPayloadCodec.fallbackBody(item.payload)
    is SocialTimelineItem.ReplyItem ->
        if (item.body.isBlank() && item.media != null) "Photo" else item.body
}

@Composable
internal fun ParticipantAvatar(
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
                    text = "Épisode ${chapterNumberLabel(chosenEpisode.number)} · ${formatTimecode(chosenEpisode.durationSeconds * 1000L)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EpisodeChooser(
                    episodes = episodes,
                    chosen = chosenEpisode,
                    medium = SocialChatMedium.ANIME,
                    onChoose = { candidate ->
                        selectedKey = candidate.key.value
                        minute = 0
                        seconds = 0
                    },
                )
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
    if (chatContext.medium == SocialChatMedium.MANGA) {
        // For a manga, "position" is the page number (from 1).
        if (chatContext.animeId <= 0L || episode.id <= 0L) {
            android.widget.Toast
                .makeText(context, "Ajoute cette œuvre à ta bibliothèque pour lire cette page", android.widget.Toast.LENGTH_LONG)
                .show()
            return
        }
        context.startActivity(
            eu.kanade.tachiyomi.ui.reader.ReaderActivity
                .newIntent(context, chatContext.animeId, episode.id)
                .putExtra("page", (positionMs - 1).toInt().coerceAtLeast(0)),
        )
        return
    }
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

internal data class SocialChatPending(
    val id: String,
    val text: String,
    val positions: List<Long>,
    val failed: Boolean = false,
    val inReplyToEventId: String? = null,
    /** Image being sent, with how many own images the timeline had when it left. */
    val image: PickedImage? = null,
    val imageBaseline: Int = 0,
)

internal fun chapterNumberLabel(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

/** Picks the page to share: the chapter (any of the work's) and a page number. */
@Composable
private fun PagePickerDialog(
    mangaTitle: String,
    chapters: List<SocialChatEpisode>,
    chapter: SocialChatEpisode,
    onDismiss: () -> Unit,
    onPick: (SocialChatEpisode, Int) -> Unit,
) {
    var selectedKey by remember(chapter.key.value) { mutableStateOf(chapter.key.value) }
    val chosen = chapters.firstOrNull { it.key.value == selectedKey } ?: chapter
    var pageText by remember { mutableStateOf("") }
    val page = pageText.toIntOrNull()
    val valid = page != null && page in 1..9999

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager une page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = mangaTitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EpisodeChooser(
                    episodes = chapters,
                    chosen = chosen,
                    medium = SocialChatMedium.MANGA,
                    onChoose = { selectedKey = it.key.value },
                )
                androidx.compose.material3.OutlinedTextField(
                    value = pageText,
                    onValueChange = { value -> pageText = value.filter(Char::isDigit).take(4) },
                    label = { Text("Numéro de page") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = valid,
                onClick = { onPick(chosen, page ?: 1) },
            ) { Text("Ajouter") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

/** Shown instead of the composer when the work is not in the user's library. */
@Composable
private fun AddToLibraryPrompt(title: String, loading: Boolean, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = "Cette œuvre n'est pas dans ta bibliothèque. Ajoute-la pour répondre à la conversation.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Button(
                onClick = onOpen,
                enabled = !loading,
                shape = CircleShape,
                modifier = Modifier.padding(top = 8.dp).height(44.dp).widthIn(min = 180.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Voir l'œuvre", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private const val SEEN_PREFS = "shiro_social_seen"

private fun loadSeenEvent(context: Context, roomId: String): String? =
    context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).getString(roomId, null)

private fun saveSeenEvent(context: Context, roomId: String, eventId: String) {
    context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).edit().putString(roomId, eventId).apply()
}
