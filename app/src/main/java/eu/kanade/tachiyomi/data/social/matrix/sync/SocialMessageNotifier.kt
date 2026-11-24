package eu.kanade.tachiyomi.data.social.matrix.sync

import android.content.Context
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.ui.social.SocialNotificationHelper
import eu.kanade.tachiyomi.ui.social.SocialNotificationState
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Decides whether a received message deserves a notification, and posts it.
 *
 * Shared by the sync service (timeline listener) and the Firebase push handler, so both
 * apply the same rules and a message seen by both is notified once.
 */
class SocialMessageNotifier(
    private val context: Context,
    private val cursor: SocialNotificationCursor,
    private val avatarLoader: SocialAvatarLoader,
) {

    /** @return true when a notification was posted. */
    suspend fun onMessage(
        roomId: String,
        eventId: String,
        timestamp: Long,
        senderMxid: String,
        senderDisplayName: String?,
        body: String,
        selfMxid: String,
        roomTopic: String?,
        roomDisplayName: String?,
    ): Boolean {
        if (senderMxid == selfMxid || body.isBlank()) return false
        // Only Shiro rooms (work + author) notify.
        val roomKey = SocialRoomKey.parseTopic(roomTopic.orEmpty()) ?: run {
            logcat(LogPriority.INFO) { "skip $eventId: not a Shiro room" }
            return false
        }
        if (!cursor.tryClaim(roomId, eventId, timestamp)) {
            logcat(LogPriority.DEBUG) { "skip $eventId: already notified or too old" }
            return false
        }

        // Already reading this conversation: no notification.
        if (SocialNotificationState.isAppForeground && SocialNotificationState.activeRoomId == roomId) {
            logcat(LogPriority.INFO) { "skip $eventId: conversation open" }
            return false
        }

        val avatar = runCatching { avatarLoader.loadProfileAvatar(senderMxid, AVATAR_SIZE) }.getOrNull()
        SocialNotificationHelper.showMessage(
            context,
            roomId = roomId,
            workId = roomKey.workId.toString(),
            authorMxid = roomKey.authorMxid,
            selfMxid = selfMxid,
            senderDisplayName = senderDisplayName?.takeIf { it.isNotBlank() }
                ?: senderMxid.substringBefore(":").removePrefix("@"),
            senderMxid = senderMxid,
            body = body,
            timestamp = timestamp,
            avatarBytes = avatar,
            roomTitle = roomDisplayName?.takeIf { it.isNotBlank() } ?: roomKey.roomName(null, null),
        )
        logcat(LogPriority.INFO) { "notification posted for $eventId" }
        return true
    }

    private companion object {
        const val AVATAR_SIZE = 128
    }
}
