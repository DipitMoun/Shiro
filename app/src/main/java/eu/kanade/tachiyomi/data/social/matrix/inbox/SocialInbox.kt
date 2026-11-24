package eu.kanade.tachiyomi.data.social.matrix.inbox

import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** One work conversation (work + author room) as listed in Messages. */
data class SocialConversation(
    val roomId: String,
    val roomKey: SocialRoomKey,
    val title: String,
    val lastSenderMxid: String?,
    val lastSenderName: String?,
    val lastBody: String?,
    val lastTimestamp: Long,
    val unreadCount: Int,
)

/**
 * Conversations of the connected account: every joined Shiro work room, newest activity
 * first. Built from the Matrix room list, which the sync keeps up to date.
 */
class SocialInbox(private val session: MatrixSessionManager) {

    private val mutex = Mutex()
    private val _conversations = MutableStateFlow<List<SocialConversation>?>(null)

    /** Null until the first load. */
    val conversations: StateFlow<List<SocialConversation>?> = _conversations.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        if (session.state.value !is SocialSessionState.Authenticated) {
            _conversations.value = emptyList()
            return@withLock
        }
        val client = session.clientOrNull() ?: return@withLock
        val list = client.rooms().mapNotNull { room ->
            runCatching {
                if (room.membership() != Membership.JOINED) return@runCatching null
                val roomKey = SocialRoomKey.parseTopic(room.topic().orEmpty()) ?: return@runCatching null
                val info = room.roomInfo()
                val latest = room.latestEvent() as? LatestEventValue.Remote
                SocialConversation(
                    roomId = room.id(),
                    roomKey = roomKey,
                    title = room.displayName()?.takeIf { it.isNotBlank() } ?: roomKey.roomName(null, null),
                    lastSenderMxid = latest?.sender,
                    lastSenderName = (latest?.profile as? ProfileDetails.Ready)?.displayName,
                    lastBody = latest?.content?.let(::previewOf),
                    lastTimestamp = latest?.timestamp?.toLong() ?: 0L,
                    unreadCount = info.numUnreadMessages.toInt(),
                )
            }.getOrNull()
        }
        _conversations.value = list.sortedByDescending { it.lastTimestamp }
    }

    private fun previewOf(content: TimelineItemContent): String? {
        val msgLike = content as? TimelineItemContent.MsgLike ?: return null
        return when (val kind = msgLike.content.kind) {
            is MsgLikeKind.Message -> kind.content.body
            is MsgLikeKind.Sticker -> "Sticker"
            else -> null
        }?.lineSequence()?.firstOrNull()?.take(PREVIEW_LENGTH)
    }

    private companion object {
        const val PREVIEW_LENGTH = 120
    }
}
