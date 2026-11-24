package eu.kanade.tachiyomi.ui.social.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.social.SocialWorkMatching
import eu.kanade.tachiyomi.data.social.activity.SocialActivityItem
import eu.kanade.tachiyomi.data.social.activity.SocialActivityKind
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import eu.kanade.tachiyomi.data.social.matrix.inbox.SocialConversation
import eu.kanade.tachiyomi.data.social.matrix.inbox.SocialInbox
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.ui.social.SocialChatContext
import eu.kanade.tachiyomi.ui.social.SocialChatScreen
import kotlinx.coroutines.delay
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/** "à l'instant", "il y a 5 min", "hier"... short and human. */
internal fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return ""
    val minutes = (now - timestamp) / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        minutes < 48 * 60 -> "hier"
        minutes < 7 * 24 * 60 -> "il y a ${minutes / (24 * 60)} j"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestamp))
    }
}

/**
 * One activity entry: the work cover, what happened, and whether it is new for the user.
 * Shared by the Social page (with the friend shown) and a friend's profile (without).
 */
@Composable
internal fun ActivityRow(
    activity: SocialActivityItem,
    actorName: String,
    showActor: Boolean,
    inLibrary: Boolean,
    onClick: () -> Unit,
) {
    val verb = when (activity.kind) {
        SocialActivityKind.ANIME_ADDED -> "a ajouté"
        SocialActivityKind.MANGA_ADDED -> "lit maintenant"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = activity.coverUrl, width = 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (showActor) "$actorName $verb" else verb.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = activity.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relativeTime(activity.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!inLibrary) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "Nouveau pour toi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        if (showActor) {
            Spacer(Modifier.width(12.dp))
            SmallAvatar(mxid = activity.actorMxid)
        }
    }
}

@Composable
private fun Cover(model: Any?, width: Int) {
    val shape = RoundedCornerShape(6.dp)
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = width.dp, height = (width * 1.42f).dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = width.dp, height = (width * 1.42f).dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

@Composable
private fun SmallAvatar(mxid: String) {
    val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
    val avatar by produceState<ImageBitmap?>(null, mxid) {
        value = avatarLoader.loadProfileAvatar(mxid, 96)?.let(::decodeAvatar)
    }
    val bitmap = avatar
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

/**
 * Messages: every work conversation, newest first, with unread counts. Reading is cheap
 * (local room list), so it simply refreshes while visible.
 */
@Composable
internal fun MessagesContent(contentPadding: PaddingValues) {
    val navigator = LocalNavigator.currentOrThrow
    val inbox = remember { Injekt.get<SocialInbox>() }
    val conversations by inbox.conversations.collectAsStateWithLifecycle()
    var library by remember { mutableStateOf<List<Anime>>(emptyList()) }

    LaunchedEffect(Unit) {
        library = runCatching { Injekt.get<GetAnimeFavorites>().await() }.getOrDefault(emptyList())
        while (true) {
            inbox.refresh()
            delay(REFRESH_MS)
        }
    }

    val localByWorkId = remember(library) {
        library.associateBy { WorkId.localStable(it.source, it.url).toString() }
    }

    fun localAnimeFor(conversation: SocialConversation): Anime? =
        localByWorkId[conversation.roomKey.workId.toString()]
            ?: SocialWorkMatching.extractTitle(conversation.title)
                ?.let { title -> SocialWorkMatching.matchTitle(title, library) { it.title } }

    val list = conversations
    when {
        list == null -> Box(
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        list.isEmpty() -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Aucune conversation", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ouvre le chat d'une œuvre depuis sa fiche pour discuter avec tes amis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            items(list, key = { it.roomId }) { conversation ->
                val local = localAnimeFor(conversation)
                ConversationRow(
                    conversation = conversation,
                    coverUrl = local?.thumbnailUrl,
                    onClick = {
                        val title = SocialWorkMatching.extractTitle(conversation.title) ?: conversation.title
                        val workId = conversation.roomKey.workId
                        navigator.push(
                            SocialChatScreen(
                                SocialChatContext(
                                    animeId = local?.id ?: 0L,
                                    work = WorkIdentity(
                                        id = workId,
                                        ids = mapOf(workId.scheme.wireName to workId.remoteId),
                                        title = title,
                                        legacyId = workId,
                                        addedAt = null,
                                    ),
                                    title = title,
                                    posterUrl = local?.thumbnailUrl,
                                    episodes = emptyList(),
                                    initialRoomId = conversation.roomId,
                                    initialAuthorMxid = conversation.roomKey.authorMxid,
                                ),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: SocialConversation, coverUrl: String?, onClick: () -> Unit) {
    val unread = conversation.unreadCount > 0
    val title = SocialWorkMatching.extractTitle(conversation.title) ?: conversation.title
    val owner = conversation.roomKey.authorMxid
    val sender = conversation.lastSenderName
        ?: conversation.lastSenderMxid?.let(::shortHandle)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = coverUrl, width = 44)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTime(conversation.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Salon de ${shortHandle(owner)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        conversation.lastBody == null -> "Aucun message"
                        sender != null -> "$sender : ${conversation.lastBody}"
                        else -> conversation.lastBody
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

private const val REFRESH_MS = 5_000L
