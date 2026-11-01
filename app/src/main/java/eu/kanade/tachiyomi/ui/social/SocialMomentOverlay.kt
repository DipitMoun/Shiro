package eu.kanade.tachiyomi.ui.social

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val PREVIEW_DURATION_MS = 30_000L
private const val NEAR_WINDOW_MS = 15_000L

private val Ink = Color(0xFFF4F0E8)
private val Muted = Color(0xFF9BA6A3)
private val Panel = Color(0xF20A0E11)
private val PanelSoft = Color(0xFF141B1E)
private val PanelRaised = Color(0xFF1B2428)
private val Line = Color(0x22FFFFFF)
private val Accent = Color(0xFF9BE7C4)
private val Unread = Color(0xFFE46A6A)

internal enum class DemoMode(val label: String) {
    Normal("Normal"),
    Empty("Fil vide"),
    Loading("Chargement"),
    Error("Erreur"),
}

internal class SocialUiState(private val context: SocialMomentContext) {
    var onSendMatrix: ((String) -> Unit)? = null
    var onReactMatrix: ((String, String) -> Unit)? = null
    var authors = SocialDemoData.authors()
    private var myThreadId = SocialDemoData.myThread(context).id

    var demoMode by mutableStateOf(DemoMode.Normal)
        private set
    var threads by mutableStateOf<List<SocialThread>>(emptyList())
        private set
    var selectedThreadId by mutableStateOf<String?>(null)
        private set
    var anchorMs by mutableLongStateOf(context.positionMs)
        private set
    var unread by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    private var localMessageId = 0

    init {
        reload(DemoMode.Normal)
    }

    val selectedThread: SocialThread?
        get() = threads.firstOrNull { it.id == selectedThreadId }

    val selectedAuthor: SocialAuthor?
        get() = authors.firstOrNull { it.id == selectedThread?.authorId }
            ?: authors.firstOrNull { it.id == SocialDemoData.ME }

    fun applyMatrixState(matrixState: eu.kanade.tachiyomi.ui.social.overlay.SocialOverlayState) {
        val timelineItems = matrixState.timeline?.items ?: emptyList()
        val myMxid = matrixState.thread?.authorMxid ?: matrixState.account?.userId ?: "Moi"

        if (timelineItems.isEmpty()) {
            authors = listOf(SocialAuthor(myMxid, "Moi", "M", 0xFF64B5F6))
            threads = listOf(SocialThread("matrix_thread", myMxid, "Mes amis", emptyList(), emptyList()))
            myThreadId = "matrix_thread"
            selectedThreadId = "matrix_thread"
            return
        }
        
        // 1. Extraire tous les auteurs uniques des messages
        val matrixAuthors = timelineItems.mapNotNull { item ->
            val sender = item.sender
            if (sender == null) return@mapNotNull null
            val initial = if (sender.startsWith("@")) sender.substring(1).take(1).uppercase() else sender.take(1).uppercase()
            SocialAuthor(
                id = sender,
                name = if (sender == myMxid) "Moi" else sender.substringBefore(":").removePrefix("@"),
                initials = initial,
                colorArgb = 0xFF64B5F6 // Couleur bleue par défaut
            )
        }.distinctBy { it.id }
        
        authors = matrixAuthors

        // 2. Convertir les items en SocialMessage
        val messages = timelineItems.mapNotNull { item ->
            when (item) {
                is eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem.MomentItem -> {
                    SocialMessage(
                        id = item.eventId,
                        authorId = item.sender ?: myMxid,
                        text = item.payload.note ?: "<Moment>",
                        positionMs = item.payload.moments.firstOrNull()?.positionMs,
                        reactions = item.reactions.associate { it.key to it.count },
                        pinned = item.pinned
                    )
                }
                is eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem.ReplyItem -> {
                    SocialMessage(
                        id = item.eventId,
                        authorId = item.sender ?: myMxid,
                        text = item.body,
                        reactions = item.reactions.associate { it.key to it.count },
                        pinned = false
                    )
                }
            }
        }.sortedBy { it.id } // Tri basique

        // 3. Créer le thread principal
        val thread = SocialThread(
            id = "matrix_thread",
            authorId = myMxid,
            audienceLabel = "Mes amis",
            messages = messages,
            moments = emptyList()
        )
        
        myThreadId = thread.id
        threads = listOf(thread)
        selectedThreadId = thread.id
    }

    fun reload(mode: DemoMode) {
        demoMode = mode
        anchorMs = context.positionMs
        localMessageId = 0
        unread = authors.associate { it.id to it.unread }

        if (mode == DemoMode.Loading || mode == DemoMode.Error) {
            threads = emptyList()
            selectedThreadId = null
            return
        }

        val base = SocialDemoData.threads(context).toMutableList()
        if (base.none { it.id == myThreadId }) {
            base += SocialDemoData.myThread(context)
        }

        threads = if (mode == DemoMode.Empty) {
            base.map { thread ->
                if (thread.authorId == SocialDemoData.ME) thread else thread.copy(messages = emptyList())
            }
        } else {
            base
        }

        selectedThreadId = threads.firstOrNull { it.authorId != SocialDemoData.ME }?.id
            ?: threads.firstOrNull()?.id
        selectedThreadId?.let(::clearUnread)
    }

    private fun clearUnread(threadId: String) {
        val authorId = threads.firstOrNull { it.id == threadId }?.authorId ?: return
        unread = unread + (authorId to 0)
    }

    fun selectThread(threadId: String) {
        selectedThreadId = threadId
        clearUnread(threadId)
    }

    fun selectAuthor(authorId: String) {
        threads.firstOrNull { it.authorId == authorId }?.let { selectThread(it.id) }
    }

    fun newChat() {
        selectedThreadId = myThreadId
    }

    fun selectAnchor(positionMs: Long) {
        anchorMs = positionMs
    }

    fun send(text: String, publish: Boolean) {
        onSendMatrix?.invoke(text)
        val body = text.trim()
        if (body.isEmpty()) return

        val targetId = if (publish) myThreadId else selectedThreadId ?: myThreadId
        val message = SocialMessage(
            id = "local-${++localMessageId}",
            authorId = SocialDemoData.ME,
            text = body,
            positionMs = anchorMs,
        )

        threads = threads.map { thread ->
            if (thread.id == targetId) {
                thread.copy(messages = thread.messages + message)
            } else {
                thread
            }
        }
        selectedThreadId = targetId
        clearUnread(targetId)
    }

    fun react(messageId: String, emoji: String) {
        threads = threads.map { thread ->
            thread.copy(
                messages = thread.messages.map { message ->
                    if (message.id != messageId) {
                        message
                    } else {
                        message.copy(
                            reactions = message.reactions + (emoji to (message.reactions[emoji] ?: 0) + 1),
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun SocialMomentOverlay(
    context: SocialMomentContext,
    posterUrl: String? = null,
    onClose: () -> Unit,
    onResume: (positionMs: Long) -> Unit,
    matrixState: eu.kanade.tachiyomi.ui.social.overlay.SocialOverlayState? = null,
    onSendMatrix: ((String) -> Unit)? = null,
    onReactMatrix: ((String, String) -> Unit)? = null,
    onPreviewStart: (positionMs: Long) -> Unit = {},
    onPreviewStop: (positionMs: Long) -> Unit = {},
    onPreviewMute: (muted: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state = remember(context) { SocialUiState(context) }
    
    LaunchedEffect(matrixState) {
        if (matrixState != null) {
            state.onSendMatrix = onSendMatrix
            state.onReactMatrix = onReactMatrix
            state.applyMatrixState(matrixState)
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scrimInteraction = remember { MutableInteractionSource() }

    var previewPlaying by remember { mutableStateOf(false) }
    var previewElapsed by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(true) }

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun closeOverlay() {
        dismissKeyboard()
        if (previewPlaying) onPreviewStop(state.anchorMs)
        onClose()
    }

    fun resumeAt(positionMs: Long) {
        dismissKeyboard()
        onResume(positionMs)
        onClose()
    }

    fun startPreview() {
        previewElapsed = 0L
        previewPlaying = true
        muted = true
        onPreviewMute(true)
        onPreviewStart(state.anchorMs)
    }

    fun stopPreview() {
        previewPlaying = false
        previewElapsed = 0L
        onPreviewStop(state.anchorMs)
    }

    val nearAuthors = remember(state.threads, state.anchorMs) {
        state.threads
            .filter { thread ->
                thread.authorId != SocialDemoData.ME &&
                    thread.messages.any { it.isNear(state.anchorMs) }
            }
            .mapNotNull { thread -> state.authors.firstOrNull { it.id == thread.authorId } }
    }

    LaunchedEffect(previewPlaying) {
        if (!previewPlaying) return@LaunchedEffect
        while (previewPlaying) {
            delay(80L)
            if (previewPlaying) {
                previewElapsed = (previewElapsed + 80L) % PREVIEW_DURATION_MS
            }
        }
    }

    BackHandler {
        if (previewPlaying) {
            stopPreview()
        } else {
            closeOverlay()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = {})
            .background(
                if (previewPlaying) Color.Black.copy(alpha = 0.02f) else Color(0xFF0B0F11),
            ),
    ) {
        if (!previewPlaying && posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(44.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val leftWidth = maxWidth * 0.45f

                val sidebarAuthors = buildList {
                    addAll(nearAuthors)
                    state.selectedAuthor
                        ?.takeIf { it.id != SocialDemoData.ME }
                        ?.let { selected -> if (none { it.id == selected.id }) add(selected) }
                    val myThread = state.threads.firstOrNull { it.authorId == SocialDemoData.ME }
                    if (myThread?.messages?.isNotEmpty() == true ||
                        state.selectedThread?.authorId == SocialDemoData.ME
                    ) {
                        state.authors.firstOrNull { it.id == SocialDemoData.ME }?.let(::add)
                    }
                }.distinctBy { it.id }

                Row(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .width(leftWidth)
                            .fillMaxHeight()
                            .alpha(if (previewPlaying) 0.62f else 1f)
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color(0xFF07090B).copy(alpha = 0.96f),
                                    0.30f to Color(0xFF07090B).copy(alpha = 0.91f),
                                    0.62f to Color(0xFF07090B).copy(alpha = 0.72f),
                                    0.84f to Color(0xFF07090B).copy(alpha = 0.34f),
                                    1f to Color.Transparent,
                                ),
                            ),
                    ) {
                        SocialSidebar(
                            authors = sidebarAuthors,
                            selectedAuthorId = state.selectedAuthor?.id,
                            onSelect = state::selectAuthor,
                            onNewChat = state::newChat,
                            onClose = ::closeOverlay,
                            modifier = Modifier
                                .width(88.dp)
                                .fillMaxHeight(),
                        )

                        ChatPane(
                            state = state,
                            onMoment = { positionMs ->
                                state.selectAnchor(positionMs)
                                startPreview()
                            },
                            onReact = state::react,
                            onSend = { text -> state.send(text, false) },
                            onRetry = { state.reload(DemoMode.Normal) },
                            onComposerFocus = { if (previewPlaying) stopPreview() },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }

                    MomentStage(
                        context = context,
                        posterUrl = posterUrl,
                        authorName = state.selectedAuthor?.name ?: "Kevin",
                        anchorMs = state.anchorMs,
                        previewPlaying = previewPlaying,
                        previewElapsed = previewElapsed,
                        muted = muted,
                        onResume = { resumeAt(state.anchorMs) },
                        onReplay = ::startPreview,
                        onPause = ::stopPreview,
                        onMute = {
                            muted = !muted
                            onPreviewMute(muted)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

    }
}

@Composable
private fun SocialSidebar(
    authors: List<SocialAuthor>,
    selectedAuthorId: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(vertical = 6.dp),
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(38.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Fermer les fils",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(authors, key = { it.id }) { author ->
                val selected = author.id == selectedAuthorId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else Color.Transparent,
                        )
                        .clickable { onSelect(author.id) }
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(author, 34.dp, selected = selected, near = false)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = author.name,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewChat)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "Nouveau chat",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 9.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatPane(
    state: SocialUiState,
    onMoment: (Long) -> Unit,
    onReact: (String, String) -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onComposerFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (state.demoMode) {
            DemoMode.Loading -> StateBody("Chargement du fil", null, null)
            DemoMode.Error -> StateBody("Impossible de charger le fil", "Vérifie ta connexion Matrix.", onRetry)
            else -> {
                val thread = state.selectedThread
                if (thread == null) {
                    EmptySelection()
                } else {
                    ChatContent(
                        state = state,
                        thread = thread,
                        onMoment = onMoment,
                        onReact = onReact,
                        onSend = onSend,
                        onComposerFocus = onComposerFocus,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatContent(
    state: SocialUiState,
    thread: SocialThread,
    onMoment: (Long) -> Unit,
    onReact: (String, String) -> Unit,
    onSend: (String) -> Unit,
    onComposerFocus: () -> Unit,
) {
    val listState = rememberLazyListState()
    var draft by rememberSaveable(thread.id) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(thread.id, thread.messages.size) {
        if (thread.messages.isNotEmpty()) {
            listState.animateScrollToItem(thread.messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (thread.messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Aucun message",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Écris le premier message de ce fil.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(thread.messages, key = { it.id }) { message ->
                    ChatMessageRow(
                        message = message,
                        author = state.authors.firstOrNull { it.id == message.authorId },
                        isMine = message.authorId == SocialDemoData.ME,
                        onMoment = onMoment,
                        onReact = { emoji -> onReact(message.id, emoji) },
                    )
                }
            }
        }

        ChatComposer(
            draft = draft,
            onDraft = { draft = it },
            focus = focus,
            onSend = {
                if (draft.isNotBlank()) {
                    onSend(draft.trim())
                    draft = ""
                }
            },
            onFocus = {
                onComposerFocus()
                keyboard?.show()
            },
        )
    }
}

@Composable
private fun ChatMessageRow(
    message: SocialMessage,
    author: SocialAuthor?,
    isMine: Boolean,
    onMoment: (Long) -> Unit,
    onReact: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isMine) {
            Avatar(author ?: return, 30.dp, selected = false, near = false)
            Spacer(Modifier.width(7.dp))
        }

        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            if (!isMine && author != null) {
                Text(
                    text = author.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                )
            }

            Surface(
                color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 15.dp,
                    topEnd = 15.dp,
                    bottomStart = if (isMine) 15.dp else 4.dp,
                    bottomEnd = if (isMine) 4.dp else 15.dp,
                ),
            ) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text(
                        text = message.text,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    message.positionMs?.let { position ->
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier.clickable { onMoment(position) },
                            ) {
                                Text(
                                    text = formatTimecode(position),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            message.reactions.entries.take(3).forEach { (emoji, count) ->
                                Text(
                                    text = "$emoji $count",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .clickable { onReact(emoji) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isMine) {
            Spacer(Modifier.width(7.dp))
            Avatar(author ?: return, 30.dp, selected = false, near = false)
        }
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraft: (String) -> Unit,
    focus: FocusRequester,
    onSend: () -> Unit,
    onFocus: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val canSend = draft.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Ajouter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(38.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(19.dp),
            border = if (focused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "Message...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onFocus()
                        },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                )
            }
        }
        IconButton(
            onClick = { if (canSend) onSend() },
            enabled = canSend,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Envoyer",
                tint = if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun Avatar(
    author: SocialAuthor,
    size: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    near: Boolean,
) {
    val background = Color(author.colorArgb)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background.copy(alpha = 0.88f))
            .border(
                width = if (selected) 2.dp else if (near) 1.5.dp else 0.dp,
                color = when {
                    selected -> Ink
                    near -> Accent
                    else -> Color.Transparent
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = author.initials,
            color = Color.Black.copy(alpha = 0.78f),
            fontSize = (size.value * 0.24f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MomentStage(
    context: SocialMomentContext,
    posterUrl: String?,
    authorName: String,
    anchorMs: Long,
    previewPlaying: Boolean,
    previewElapsed: Long,
    muted: Boolean,
    onResume: () -> Unit,
    onReplay: () -> Unit,
    onPause: () -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (!previewPlaying) {
            PosterCard(
                context = context,
                posterUrl = posterUrl,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 100.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StageAction("Reprendre", onResume)
                Spacer(Modifier.width(8.dp))
                if (previewPlaying) {
                    PreviewBand(
                        previewElapsed = previewElapsed,
                        muted = muted,
                        onPause = onPause,
                        onMute = onMute,
                    )
                } else {
                    Button(
                        onClick = onReplay,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 9.dp),
                    ) {
                        Text("Aperçu", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                StageAction("Revoir", onReplay)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Moment de $authorName · ${formatTimecode(anchorMs)}",
                color = Muted.copy(alpha = 0.8f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StageAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, Line),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, color = Ink, fontSize = 10.sp)
    }
}

@Composable
private fun PreviewBand(
    previewElapsed: Long,
    muted: Boolean,
    onPause: () -> Unit,
    onMute: () -> Unit,
) {
    Surface(
        color = PanelSoft.copy(alpha = 0.95f),
        contentColor = Ink,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier
                .width(206.dp)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { (previewElapsed.toFloat() / PREVIEW_DURATION_MS).coerceIn(0f, 1f) },
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Accent,
                trackColor = Color.White.copy(alpha = 0.12f),
            )
            Spacer(Modifier.width(5.dp))
            IconButton(onClick = onMute, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (muted) "Activer le son" else "Couper le son",
                    tint = Muted,
                    modifier = Modifier.size(13.dp),
                )
            }
            IconButton(onClick = onPause, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = Ink, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun PosterCard(
    context: SocialMomentContext,
    posterUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(132.dp)
            .aspectRatio(2f / 3f),
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        shadowElevation = 18.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF284A46),
                                Color(0xFF4D342A),
                                Color(0xFF101719),
                            ),
                        ),
                    ),
            )
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.78f),
                                ),
                            ),
                        ),
                )
            }
            Text(
                text = context.workTitle,
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun StateBody(title: String, subtitle: String?, retry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (retry == null) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.PeopleAlt, contentDescription = null, tint = Muted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        subtitle?.let {
            Text(it, color = Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        retry?.let {
            Spacer(Modifier.height(12.dp))
            Button(onClick = it, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) {
                Text("Réessayer", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EmptySelection() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.PeopleAlt, contentDescription = null, tint = Muted.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text("Choisis un ami", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Ses messages apparaîtront ici.", color = Muted, fontSize = 9.5.sp)
    }
}

private fun SocialMessage.isNear(anchorMs: Long): Boolean =
    positionMs?.let { abs(it - anchorMs) <= NEAR_WINDOW_MS } == true
