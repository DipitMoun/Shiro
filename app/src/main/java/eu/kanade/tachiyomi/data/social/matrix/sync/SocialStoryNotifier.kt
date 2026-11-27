package eu.kanade.tachiyomi.data.social.matrix.sync

import android.content.Context
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.story.StoryExpiry
import eu.kanade.tachiyomi.ui.social.SocialNotificationHelper
import eu.kanade.tachiyomi.ui.social.SocialNotificationState

/**
 * "Someone posted a story": one notification per friend, whatever the number of stories, and only
 * for stories that are still active. The cursor keeps the live listener from notifying twice.
 */
class SocialStoryNotifier(
    private val context: Context,
    private val cursor: SocialNotificationCursor,
    private val avatarLoader: SocialAvatarLoader,
    private val friends: SocialFriendStore,
) {

    suspend fun onStory(roomId: String, eventId: String, timestamp: Long, ownerMxid: String): Boolean {
        if (!friends.isFriend(ownerMxid)) return false
        if (!StoryExpiry.isActive(timestamp, System.currentTimeMillis())) return false
        if (!cursor.tryClaim(roomId, eventId, timestamp)) return false
        // The ring on the Social page already says it.
        if (SocialNotificationState.isAppForeground) return false

        val name = friends.friends.value.firstOrNull { it.mxid == ownerMxid }?.displayName?.takeIf { it.isNotBlank() }
            ?: ownerMxid.substringBefore(':').removePrefix("@")
        val avatar = runCatching { avatarLoader.loadProfileAvatar(ownerMxid, 128) }.getOrNull()
        SocialNotificationHelper.showStory(context, ownerMxid, name, timestamp, avatar)
        return true
    }
}
