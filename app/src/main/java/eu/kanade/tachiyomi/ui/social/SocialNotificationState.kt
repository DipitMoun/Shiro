package eu.kanade.tachiyomi.ui.social

/**
 * Global observable state for social notifications.
 *
 * Updated by:
 * - [eu.kanade.tachiyomi.ui.main.MainActivity] for the foreground state.
 * - [eu.kanade.tachiyomi.ui.social.SocialChatScreen] for the active room.
 * - [eu.kanade.tachiyomi.data.social.matrix.sync.MatrixSyncService] for the last notified event.
 */
object SocialNotificationState {
    @Volatile
    var isAppForeground: Boolean = false

    /** The id of the room currently visible in the chat screen, if any. */
    @Volatile
    var activeRoomId: String? = null

    /** Last event id that triggered a notification, to avoid duplicates. */
    @Volatile
    var lastNotifiedEventId: String? = null
}