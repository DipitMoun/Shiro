package eu.kanade.tachiyomi.data.social.matrix.sync

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers, per room, the newest message already notified or read.
 *
 * It survives process restarts, so messages received while the sync service was stopped
 * are notified when it comes back instead of being dropped, and nothing is notified twice.
 */
class SocialNotificationCursor(context: Context) {

    private val prefs = context.getSharedPreferences("social_notification_cursor", Context.MODE_PRIVATE)
    private val recentEventIds = LinkedHashSet<String>()

    /** Messages older than the first run are history, not news. */
    private val baseline: Long = prefs.getLong(KEY_BASELINE, 0L).takeIf { it > 0L }
        ?: System.currentTimeMillis().also { now -> prefs.edit { putLong(KEY_BASELINE, now) } }

    @Synchronized
    fun shouldNotify(roomId: String, eventId: String, timestamp: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (eventId in recentEventIds) return false
        val floor = maxOf(prefs.getLong(roomKey(roomId), 0L), baseline, now - MAX_CATCH_UP_MS)
        return timestamp > floor
    }

    @Synchronized
    fun markNotified(roomId: String, eventId: String, timestamp: Long) {
        recentEventIds += eventId
        if (recentEventIds.size > MAX_RECENT_IDS) {
            recentEventIds.remove(recentEventIds.first())
        }
        advance(roomId, timestamp)
    }

    /**
     * Atomically checks and records an event. The push handler and the timeline listener may
     * see the same message at the same time: only the first caller gets true.
     */
    @Synchronized
    fun tryClaim(roomId: String, eventId: String, timestamp: Long): Boolean {
        if (!shouldNotify(roomId, eventId, timestamp)) return false
        markNotified(roomId, eventId, timestamp)
        return true
    }

    /** The user read the room (in the app or from a notification action). */
    @Synchronized
    fun markRead(roomId: String, timestamp: Long = System.currentTimeMillis()) {
        advance(roomId, timestamp)
    }

    private fun advance(roomId: String, timestamp: Long) {
        val key = roomKey(roomId)
        if (timestamp > prefs.getLong(key, 0L)) {
            prefs.edit { putLong(key, timestamp) }
        }
    }

    private fun roomKey(roomId: String) = "room:$roomId"

    private companion object {
        const val KEY_BASELINE = "baseline"
        const val MAX_RECENT_IDS = 500

        /** After a long time offline, older messages are not worth a notification burst. */
        const val MAX_CATCH_UP_MS = 3L * 24 * 60 * 60 * 1000
    }
}
