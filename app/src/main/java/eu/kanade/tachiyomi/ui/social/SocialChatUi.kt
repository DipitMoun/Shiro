package eu.kanade.tachiyomi.ui.social

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.social.matrix.DeliveryState
import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Rows: what the list really shows (days, "new messages" marker, grouped messages)
// ---------------------------------------------------------------------------

internal sealed interface ChatRow {
    val key: String

    data class Day(val date: LocalDate) : ChatRow {
        override val key = "day-$date"
    }

    data object NewMarker : ChatRow {
        override val key = "new-marker"
    }

    data class Message(
        val item: SocialTimelineItem,
        /** First bubble of a run by the same author. */
        val first: Boolean,
        /** Last bubble of a run: it carries the avatar. */
        val last: Boolean,
        /** Friends whose last-read point is this message. */
        val readers: List<SocialChatFriend>,
        /** Own message that someone has read (or read past). */
        val seen: Boolean,
    ) : ChatRow {
        override val key = item.eventId
    }
}

private const val GROUP_GAP_MINUTES = 5L

/**
 * Turns the timeline into list rows. [unseenAfterEventId] is the newest message the user had
 * seen when they left; the first message of someone else after it gets a "new messages" marker.
 */
internal fun buildChatRows(
    items: List<SocialTimelineItem>,
    selfMxid: String,
    participants: Map<String, SocialChatFriend>,
    unseenAfterEventId: String?,
): List<ChatRow> {
    if (items.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val seenIndex = items.indexOfLast { item -> item.readBy.any { it != selfMxid } }
    val unseenStart = unseenAfterEventId
        ?.let { id -> items.indexOfFirst { it.eventId == id } }
        ?.takeIf { it >= 0 }
        ?.let { seen -> (seen + 1 until items.size).firstOrNull { items[it].sender != selfMxid } }

    val rows = ArrayList<ChatRow>(items.size + 4)
    var lastDay: LocalDate? = null
    items.forEachIndexed { index, item ->
        val day = item.sentAt.atZone(zone).toLocalDate()
        if (day != lastDay) {
            rows += ChatRow.Day(day)
            lastDay = day
        }
        if (index == unseenStart) rows += ChatRow.NewMarker

        val previous = items.getOrNull(index - 1)
        val next = items.getOrNull(index + 1)
        fun joins(a: SocialTimelineItem?, b: SocialTimelineItem?, bIndex: Int) =
            a != null && b != null &&
                bIndex != unseenStart &&
                a.sender == b.sender &&
                a.sentAt.atZone(zone).toLocalDate() == b.sentAt.atZone(zone).toLocalDate() &&
                Duration.between(a.sentAt, b.sentAt).abs().toMinutes() < GROUP_GAP_MINUTES
        val readers = item.readBy
            .filter { it != selfMxid && it != item.sender }
            .map { participants[it] ?: fallbackParticipant(it) }

        rows += ChatRow.Message(
            item = item,
            first = !joins(previous, item, index),
            last = !joins(item, next, index + 1),
            readers = readers,
            seen = item.sender == selfMxid && index <= seenIndex,
        )
    }
    return rows
}

// ---------------------------------------------------------------------------
// Separators
// ---------------------------------------------------------------------------

private val dayFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val dayYearFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Aujourd'hui"
        today.minusDays(1) -> "Hier"
        else -> date.format(if (date.year == today.year) dayFormatter else dayYearFormatter)
    }
}

@Composable
internal fun ChatDayDivider(date: LocalDate) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            shape = CircleShape,
        ) {
            Text(
                text = dayLabel(date),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun ChatNewMarker() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            text = "Nouveaux messages",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

// ---------------------------------------------------------------------------
// Bubbles
// ---------------------------------------------------------------------------

private val tailRadius = 5.dp
private val bubbleRadius = 19.dp

private fun bubbleShape(mine: Boolean, first: Boolean, last: Boolean): RoundedCornerShape =
    if (mine) {
        RoundedCornerShape(
            topStart = bubbleRadius,
            topEnd = if (first) bubbleRadius else tailRadius,
            bottomEnd = if (last) tailRadius else tailRadius,
            bottomStart = bubbleRadius,
        )
    } else {
        RoundedCornerShape(
            topStart = if (first) bubbleRadius else tailRadius,
            topEnd = bubbleRadius,
            bottomEnd = bubbleRadius,
            bottomStart = tailRadius,
        )
    }

/** What a bubble needs to draw one moment card. */
internal data class ChatMomentModel(
    val episode: SocialChatEpisode,
    val position: Long,
)

internal data class ChatBubbleContent(
    val text: String,
    val moments: List<ChatMomentModel>,
    val replyTarget: SocialTimelineItem?,
    /** Image of a received message. */
    val image: eu.kanade.tachiyomi.data.social.matrix.ChatMedia? = null,
    /** Image still being sent: shown from the local bytes. */
    val pendingImage: PickedImage? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubble(
    participant: SocialChatFriend,
    content: ChatBubbleContent,
    timestamp: java.time.Instant?,
    mine: Boolean,
    first: Boolean,
    last: Boolean,
    medium: SocialChatMedium,
    posterUrl: Any?,
    participants: Map<String, SocialChatFriend>,
    selfMxid: String,
    reactions: List<eu.kanade.tachiyomi.data.social.matrix.ReactionSummary>,
    readers: List<SocialChatFriend>,
    seen: Boolean,
    state: DeliveryState,
    onOpenMoment: (SocialChatEpisode, Long, Boolean) -> Unit,
    onJumpTo: (String) -> Unit,
    onReply: () -> Unit,
    onLongPress: (() -> Unit)?,
    onReaction: ((String) -> Unit)?,
    onRetry: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    val canSwipe = state == DeliveryState.SENT && onLongPress != null

    Box(Modifier.fillMaxWidth()) {
        // Reply glyph revealed behind the bubble while it is dragged.
        val progress = (-offset.value / trigger).coerceIn(0f, 1f)
        if (progress > 0f) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
                    .size(22.dp)
                    .scale(0.6f + 0.4f * progress)
                    .alpha(progress),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(
                    if (canSwipe) {
                        Modifier.pointerInput(Unit) {
                            var fired = false
                            detectHorizontalDragGestures(
                                onDragStart = { fired = false },
                                onDragEnd = {
                                    scope.launch {
                                        if (fired) onReply()
                                        offset.animateTo(0f)
                                    }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f) } },
                            ) { change, drag ->
                                change.consume()
                                scope.launch {
                                    val next = (offset.value + drag * 0.7f).coerceIn(-trigger * 1.25f, 0f)
                                    offset.snapTo(next)
                                    if (!fired && next <= -trigger) {
                                        fired = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else if (fired && next > -trigger) {
                                        fired = false
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!mine) {
                if (last) {
                    ParticipantAvatar(participant = participant, size = 30.dp)
                } else {
                    Spacer(Modifier.width(30.dp))
                }
                Spacer(Modifier.width(6.dp))
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .animateContentSize(),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                val bubbleColor = when {
                    state == DeliveryState.FAILED -> MaterialTheme.colorScheme.errorContainer
                    mine -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                }
                val bubbleText = when {
                    state == DeliveryState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                    mine -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    color = bubbleColor,
                    contentColor = bubbleText,
                    shape = bubbleShape(mine, first, last),
                    modifier = Modifier
                        .clip(bubbleShape(mine, first, last))
                        .combinedClickable(
                            onClick = { if (state == DeliveryState.FAILED) onRetry?.invoke() },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress?.invoke()
                            },
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
                        if (!mine && first) {
                            Text(
                                text = participant.name,
                                color = participantColor(participant.mxid),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }

                        content.replyTarget?.let { target ->
                            ChatReplyQuote(
                                target = target,
                                participants = participants,
                                onClick = { onJumpTo(target.eventId) },
                            )
                        }

                        if (content.image != null || content.pendingImage != null) {
                            ChatImage(media = content.image, local = content.pendingImage)
                        }

                        content.moments.forEach { moment ->
                            ChatMomentCard(
                                medium = medium,
                                episode = moment.episode,
                                position = moment.position,
                                posterUrl = posterUrl,
                                onOpen = onOpenMoment,
                            )
                        }

                        if (content.text.isNotBlank()) {
                            Text(
                                text = content.text,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                modifier = Modifier.padding(
                                    top = if (content.moments.isNotEmpty() || content.replyTarget != null || content.image != null || content.pendingImage != null) 6.dp else 0.dp,
                                ),
                            )
                        }

                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (state == DeliveryState.FAILED) {
                                Text(
                                    text = "Échec · Réessayer",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(12.dp))
                            } else {
                                timestamp?.let {
                                    Text(
                                        text = chatTimeFormatter.format(it),
                                        fontSize = 10.sp,
                                        color = bubbleText.copy(alpha = 0.6f),
                                    )
                                }
                                if (mine) {
                                    val tint = if (seen) MaterialTheme.colorScheme.primary else bubbleText.copy(alpha = 0.55f)
                                    Icon(
                                        imageVector = when {
                                            state == DeliveryState.PENDING -> Icons.Outlined.AccessTime
                                            seen -> Icons.Filled.DoneAll
                                            else -> Icons.Filled.Check
                                        },
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        reactions.take(8).forEach { reaction ->
                            val byMe = reaction.reactions.any { it.sender == selfMxid }
                            Surface(
                                color = if (byMe) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = if (byMe) {
                                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onReaction?.invoke(reaction.key) },
                            ) {
                                Text(
                                    text = if (reaction.count > 1) "${reaction.key} ${reaction.count}" else reaction.key,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                if (readers.isNotEmpty()) {
                    ReadBeads(readers = readers, mine = mine)
                }
            }
        }
    }
}

/**
 * Small avatars under the last message each friend has read. When the read point moves to a
 * newer message, the avatar appears on that one and slides down into place.
 */
@Composable
private fun ReadBeads(readers: List<SocialChatFriend>, mine: Boolean) {
    Row(
        modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        readers.forEach { reader ->
            androidx.compose.runtime.key(reader.mxid) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = visible,
                    enter = fadeIn(tween(220)) +
                        slideInVertically(tween(320)) { -it * 2 } +
                        scaleIn(tween(320), initialScale = 0.4f),
                ) {
                    ParticipantAvatar(
                        participant = reader,
                        size = 15.dp,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.background.copy(alpha = 0.8f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatReplyQuote(
    target: SocialTimelineItem,
    participants: Map<String, SocialChatFriend>,
    onClick: () -> Unit,
) {
    val author = participants[target.sender] ?: fallbackParticipant(target.sender)
    val accent = participantColor(author.mxid)
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = author.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
            )
            Text(
                text = messageText(target).replace('\n', ' '),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.78f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Moment cards: same card for anime (timecode) and manga (page)
// ---------------------------------------------------------------------------

@Composable
internal fun ChatMomentCard(
    medium: SocialChatMedium,
    episode: SocialChatEpisode,
    position: Long,
    posterUrl: Any?,
    onOpen: (SocialChatEpisode, Long, Boolean) -> Unit,
) {
    val manga = medium == SocialChatMedium.MANGA
    var revealed by rememberSaveable(episode.key.value, position) { mutableStateOf(false) }
    val transition = updateTransition(revealed, label = "spoiler")
    val blur by transition.animateDp(transitionSpec = { tween(380) }, label = "blur") { if (it) 0.dp else 24.dp }
    val veil by transition.animateFloat(transitionSpec = { tween(380) }, label = "veil") { if (it) 0.18f else 0.5f }

    val durationMs = episode.durationSeconds.takeIf { it > 0L }?.times(1000L)
    val label: String
    val subtitle: String
    val progress: Float?
    if (manga) {
        label = "Ch. ${chapterNumberLabel(episode.number)} · p. $position"
        subtitle = episode.title?.takeIf { it.isNotBlank() } ?: "Chapitre ${chapterNumberLabel(episode.number)}"
        progress = null
    } else {
        val start = (position - 15_000L).coerceAtLeast(0L)
        val end = durationMs?.let { (position + 15_000L).coerceAtMost(it) } ?: (position + 15_000L)
        label = "${episode.key.value} · ${formatTimecode(start)} – ${formatTimecode(end)}"
        subtitle = episode.title?.takeIf { it.isNotBlank() } ?: "Épisode ${chapterNumberLabel(episode.number)}"
        progress = durationMs?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
    }

    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .widthIn(min = 230.dp)
            .height(if (manga) 150.dp else 132.dp)
            .clip(shape)
            .clickable { if (!revealed) revealed = true else onOpen(episode, position, false) },
    ) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(blur),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = veil), Color.Black.copy(alpha = 0.78f)),
                    ),
                ),
        )

        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )

        if (!revealed) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White)
                Text("Spoiler", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Toucher pour révéler", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = subtitle,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceAtLeast(0.02f))
                                .fillMaxHeight()
                                .background(Color.White),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (manga) {
                        MomentAction(Icons.AutoMirrored.Outlined.MenuBook, "Lire cette page") {
                            onOpen(episode, position, false)
                        }
                    } else {
                        MomentAction(null, "Boucler 30 s") { onOpen(episode, position, true) }
                        MomentAction(null, "Reprendre") { onOpen(episode, position, false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentAction(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, onClick: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.2f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------------------
// Typing
// ---------------------------------------------------------------------------

@Composable
internal fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0 + index * 120
                        -3f at 220 + index * 120
                        0f at 440 + index * 120
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .offset(y = lift.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

internal fun typingLabel(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]} écrit"
    2 -> "${names[0]} et ${names[1]} écrivent"
    else -> "Plusieurs personnes écrivent"
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

private const val DRAFT_PREFS = "shiro_social_drafts"

private fun loadDraft(context: Context, key: String): String =
    context.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()

private fun saveDraft(context: Context, key: String, value: String) {
    context.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE).edit().apply {
        if (value.isBlank()) remove(key) else putString(key, value)
    }.apply()
}

@Composable
internal fun ChatComposer(
    draftKey: String,
    chips: List<Long>,
    medium: SocialChatMedium,
    onRemoveChip: (Long) -> Unit,
    onCommands: () -> Unit,
    onTimecodeRequest: () -> Unit,
    onTyping: () -> Unit,
    onAttach: () -> Unit,
    onSend: (String) -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(draftKey) { mutableStateOf(loadDraft(context, draftKey)) }
    val canSend = draft.isNotBlank() || chips.isNotEmpty()

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
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (medium == SocialChatMedium.MANGA) {
                                    Icons.AutoMirrored.Outlined.MenuBook
                                } else {
                                    Icons.Outlined.AccessTime
                                },
                                contentDescription = null,
                                modifier = Modifier.padding(start = 8.dp).size(13.dp),
                            )
                            Text(
                                text = if (medium == SocialChatMedium.MANGA) "p. $position" else formatTimecode(position),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            )
                            IconButton(onClick = { onRemoveChip(position) }, modifier = Modifier.size(26.dp)) {
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

        Row(verticalAlignment = Alignment.Bottom) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = onCommands) {
                        Icon(Icons.Default.Add, contentDescription = "Commandes", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).padding(end = 4.dp)) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "Message",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { value ->
                                val command = if (medium == SocialChatMedium.MANGA) "/page" else "/timecode"
                                val next = if (value.contains(command, ignoreCase = true)) {
                                    onTimecodeRequest()
                                    value.replace(command, "", ignoreCase = true)
                                } else {
                                    value
                                }
                                if (next != draft) {
                                    draft = next
                                    saveDraft(context, draftKey, next)
                                    if (next.isNotBlank()) onTyping()
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            maxLines = 6,
                        )
                    }
                    IconButton(onClick = onAttach) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = "Image ou GIF",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            val sendScale by animateFloatAsState(if (canSend) 1f else 0.82f, tween(180), label = "send")
            Surface(
                color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape,
                modifier = Modifier
                    .size(48.dp)
                    .scale(sendScale)
                    .clip(CircleShape)
                    .clickable(enabled = canSend) {
                        onSend(draft)
                        draft = ""
                        saveDraft(context, draftKey, "")
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer", modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Episode / chapter selection for moments
// ---------------------------------------------------------------------------

/**
 * Chooser used by both moment pickers: previous / next arrows around a scrolling row of
 * numbered chips that keeps the chosen one in view.
 */
@Composable
internal fun EpisodeChooser(
    episodes: List<SocialChatEpisode>,
    chosen: SocialChatEpisode,
    medium: SocialChatMedium,
    onChoose: (SocialChatEpisode) -> Unit,
) {
    val ordered = remember(episodes) { episodes.distinctBy { it.key.value }.sortedBy { it.number } }
    val index = ordered.indexOfFirst { it.key.value == chosen.key.value }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(chosen.key.value) {
        listState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    val prefix = if (medium == SocialChatMedium.MANGA) "Ch." else "Ép."

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (medium == SocialChatMedium.MANGA) "Chapitre" else "Épisode",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { ordered.getOrNull(index - 1)?.let(onChoose) },
                enabled = index > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Précédent")
            }
            androidx.compose.foundation.lazy.LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(ordered.size, key = { ordered[it].key.value }) { position ->
                    val candidate = ordered[position]
                    val selected = candidate.key.value == chosen.key.value
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        },
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onChoose(candidate) },
                    ) {
                        Text(
                            text = "$prefix ${chapterNumberLabel(candidate.number)}",
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            IconButton(
                onClick = { ordered.getOrNull(index + 1)?.let(onChoose) },
                enabled = index < ordered.lastIndex,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Suivant")
            }
        }
        chosen.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------
// Images and GIFs
// ---------------------------------------------------------------------------

/**
 * An image of the chat: from the Matrix server for a received one, from the local bytes while it
 * is being sent. Tap to see it full screen.
 */
@Composable
internal fun ChatImage(
    media: eu.kanade.tachiyomi.data.social.matrix.ChatMedia?,
    local: PickedImage?,
) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf(false) }

    val loaded by androidx.compose.runtime.produceState<Result<ByteArray>?>(
        initialValue = media?.let { eu.kanade.tachiyomi.data.social.matrix.media.SocialMediaLoader.cached(it) }
            ?.let { Result.success(it) },
        media?.cacheKey,
    ) {
        if (media != null && value == null) {
            value = runCatching { eu.kanade.tachiyomi.data.social.matrix.media.SocialMediaLoader.load(media) }
        }
    }
    val bytes = local?.bytes ?: loaded?.getOrNull()
    val width = local?.width ?: media?.width
    val height = local?.height ?: media?.height
    val ratio = if (width != null && height != null && width > 0 && height > 0) {
        (width.toFloat() / height).coerceIn(0.6f, 2f)
    } else {
        4f / 3f
    }
    val key = local?.filename ?: media?.cacheKey.orEmpty()

    Box(
        modifier = Modifier
            .widthIn(min = 160.dp, max = 260.dp)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = bytes != null) { viewing = true },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bytes != null -> AsyncImage(
                model = coil3.request.ImageRequest.Builder(context)
                    .data(bytes)
                    .memoryCacheKey(key)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            loaded?.isFailure == true -> Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = "Image indisponible",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }

    if (viewing && bytes != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable { viewing = false },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = coil3.request.ImageRequest.Builder(context)
                        .data(bytes)
                        .memoryCacheKey(key + "/full")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
