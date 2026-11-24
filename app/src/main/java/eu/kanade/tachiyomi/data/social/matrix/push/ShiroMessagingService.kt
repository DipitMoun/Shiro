package eu.kanade.tachiyomi.data.social.matrix.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import eu.kanade.tachiyomi.data.social.activity.SocialActivityCodec
import eu.kanade.tachiyomi.data.social.activity.SocialActivityService
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.friends.SocialRoomInvite
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import eu.kanade.tachiyomi.data.social.matrix.session.RustSessionDelegate
import eu.kanade.tachiyomi.data.social.matrix.sync.MatrixSyncService
import eu.kanade.tachiyomi.data.social.matrix.sync.SocialMessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TimelineEventContent
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Receives the Firebase pushes relayed by the Shiro push gateway.
 *
 * A push only carries room and event ids. The event is fetched and decrypted locally with
 * the Matrix notification client, then shown like any other message; the sync service is
 * (re)started so the conversation keeps flowing while the device is awake.
 */
class ShiroMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        runBlocking {
            runCatching { Injekt.get<SocialPushManager>().ensureRegistered(newToken = token) }
                .onFailure { logcat(LogPriority.WARN, it) { "pusher update after token refresh failed" } }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val roomId = message.data["room_id"]?.takeIf { it.isNotBlank() } ?: return
        val eventId = message.data["event_id"]?.takeIf { it.isNotBlank() }
        logcat(LogPriority.INFO) { "push received room=$roomId event=$eventId priority=${message.priority}" }

        // (Re)start the sync service if Android or an OEM freezer killed it: it keeps the
        // process alive and the live listeners running.
        runCatching { MatrixSyncService.start(this) }
            .onFailure { logcat(LogPriority.WARN, it) { "sync service start from push failed" } }

        // Fast path. The Matrix SDK calls do not react to coroutine cancellation, so the
        // timeout is applied to the wait, not to the work: a stuck call can no longer block
        // the push handler, and the live listeners still deliver the notification.
        runBlocking {
            val work = handlerScope.async {
                runCatching { handle(roomId, eventId) }
                    .onFailure { logcat(LogPriority.WARN, it) { "push handling failed" } }
            }
            withTimeoutOrNull(HANDLE_TIMEOUT_MS) { work.await() }
                ?: logcat(LogPriority.WARN) { "push fast path timed out, live listeners take over" }
        }
    }

    private suspend fun handle(roomId: String, eventId: String?) {
        val session = Injekt.get<MatrixSessionManager>()
        if (!MatrixSessionBootstrap.ensureRestored(session)) {
            logcat(LogPriority.WARN) { "push: no Matrix session" }
            return
        }
        val selfMxid = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId ?: return
        if (eventId == null) return

        val client = session.clientOrNull() ?: return
        val notificationClient = notificationClient(client) ?: run {
            logcat(LogPriority.WARN) { "push: notification client unavailable" }
            return
        }

        val item = when (val status = notificationClient.getNotification(roomId, eventId)) {
            is NotificationStatus.Event -> status.item
            else -> {
                logcat(LogPriority.INFO) { "push: event not notifiable (${status::class.simpleName})" }
                return
            }
        }

        when (val event = item.event) {
            is NotificationEvent.Invite -> {
                Injekt.get<SocialInviteMonitor>().onInvitePushed(
                    SocialRoomInvite(
                        roomId = roomId,
                        name = item.roomInfo.displayName?.takeIf { it.isNotBlank() } ?: "Room Matrix",
                        inviterMxid = event.sender,
                        inviterDisplayName = item.senderInfo.displayName,
                        inviterAvatarUrl = item.senderInfo.avatarUrl,
                    ),
                )
                logcat(LogPriority.INFO) { "push: friend request from ${event.sender}" }
            }
            is NotificationEvent.Timeline -> {
                val timelineEvent = event.event
                val content = timelineEvent.content()
                activityOf(content)?.let { (msgtype, body) ->
                    Injekt.get<SocialActivityService>().onPushed(
                        roomId = roomId,
                        eventId = timelineEvent.eventId(),
                        senderMxid = timelineEvent.senderId(),
                        msgtype = msgtype,
                        body = body,
                        timestamp = timelineEvent.timestamp().toLong(),
                    )
                    logcat(LogPriority.INFO) { "push: friend activity" }
                    return
                }
                val body = bodyOf(content) ?: run {
                    logcat(LogPriority.INFO) { "push: event has no text body" }
                    return
                }
                val posted = Injekt.get<SocialMessageNotifier>().onMessage(
                    roomId = roomId,
                    eventId = timelineEvent.eventId(),
                    timestamp = timelineEvent.timestamp().toLong(),
                    senderMxid = timelineEvent.senderId(),
                    senderDisplayName = item.senderInfo.displayName,
                    body = body,
                    selfMxid = selfMxid,
                    // The notification preview may omit the topic, where the work id lives.
                    roomTopic = item.roomInfo.topic
                        ?: runCatching { client.getRoom(roomId)?.topic() }.getOrNull(),
                    roomDisplayName = item.roomInfo.displayName,
                )
                logcat(LogPriority.INFO) { "push handled, notification posted=$posted" }
            }
        }
    }

    private fun activityOf(content: TimelineEventContent): Pair<String, String>? {
        val messageLike = content as? TimelineEventContent.MessageLike ?: return null
        val message = messageLike.content as? MessageLikeEventContent.RoomMessage ?: return null
        val other = message.messageType as? MessageType.Other ?: return null
        return (other.msgtype to other.body).takeIf { SocialActivityCodec.isActivityMsgtype(other.msgtype) }
    }

    private fun bodyOf(content: TimelineEventContent): String? {
        val messageLike = content as? TimelineEventContent.MessageLike ?: return null
        val message = messageLike.content as? MessageLikeEventContent.RoomMessage ?: return null
        return when (val type = message.messageType) {
            is MessageType.Text -> type.content.body
            is MessageType.Notice -> type.content.body
            is MessageType.Emote -> type.content.body
            else -> null
        }
    }

    private companion object {
        const val HANDLE_TIMEOUT_MS = 8_000L

        private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val SYNC_SERVICE_WAIT_STEPS = 25
        const val SYNC_SERVICE_WAIT_STEP_MS = 200L

        private val clientMutex = Mutex()
        private var cachedClient: Client? = null
        private var cachedNotificationClient: NotificationClient? = null

        /** One notification client per Matrix client, reusing the running sync service. */
        suspend fun notificationClient(client: Client): NotificationClient? = clientMutex.withLock {
            if (cachedClient === client) cachedNotificationClient?.let { return@withLock it }
            // Right after a cold-start restore the sync service is still being built.
            val delegate = Injekt.get<RustSessionDelegate>()
            var syncService = delegate.syncServiceOrNull()
            repeat(SYNC_SERVICE_WAIT_STEPS) {
                if (syncService != null) return@repeat
                delay(SYNC_SERVICE_WAIT_STEP_MS)
                syncService = delegate.syncServiceOrNull()
            }
            val service = syncService ?: return@withLock null
            runCatching { client.notificationClient(NotificationProcessSetup.SingleProcess(service)) }
                .onFailure { logcat(LogPriority.WARN, it) { "notification client unavailable" } }
                .getOrNull()
                ?.also {
                    cachedClient = client
                    cachedNotificationClient = it
                }
        }
    }
}
