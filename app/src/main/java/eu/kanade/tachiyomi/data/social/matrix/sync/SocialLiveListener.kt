package eu.kanade.tachiyomi.data.social.matrix.sync

import eu.kanade.tachiyomi.data.social.activity.SocialActivityService
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import tachiyomi.core.common.util.system.logcat
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.util.concurrent.ConcurrentHashMap

/**
 * Live view of the Matrix account: joined rooms for new messages, invited rooms for friend
 * requests.
 *
 * Runs in the app process. While Shiro is open it needs no foreground service (and so no
 * permanent notification); in the background the process is woken by a push and kept alive
 * briefly by [MatrixSyncService]. Subscribing the room list also keeps the main sliding sync
 * fed, which the Matrix notification client relies on.
 */
class SocialLiveListener(
    private val session: MatrixSessionManager,
    private val inviteMonitor: SocialInviteMonitor,
    private val notifier: SocialMessageNotifier,
    private val activity: SocialActivityService,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerJobs = ConcurrentHashMap<String, Job>()
    private val inviteRefreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile private var started = false

    @Volatile private var attachedClient: Client? = null

    @Volatile private var roomListResult: RoomListEntriesWithDynamicAdaptersResult? = null

    @Volatile private var roomListTask: TaskHandle? = null

    /** Last time anything arrived; lets a short-lived service stop once things are quiet. */
    @Volatile
    var lastActivityAt: Long = 0L
        private set

    /** Idempotent: restores the session if needed and attaches the listeners once. */
    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        markActivity()

        scope.launch {
            inviteRefreshRequests.debounce(INVITE_REFRESH_DEBOUNCE_MS).collect {
                runCatching { inviteMonitor.refresh() }
                    .onFailure { logcat(LogPriority.WARN, it) { "invite refresh failed" } }
            }
        }

        scope.launch {
            var retryDelay = RESTORE_RETRY_MIN_MS
            while (!MatrixSessionBootstrap.ensureRestored(session)) {
                // Logged out: nothing to listen to.
                if (session.state.value is SocialSessionState.Unauthenticated) {
                    stop()
                    return@launch
                }
                // Network down at boot, or a login in progress: try again later.
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(RESTORE_RETRY_MAX_MS)
            }

            val selfMxid = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId
                ?: return@launch
            val client = session.clientOrNull() ?: return@launch
            attachedClient = client
            activity.start()

            client.rooms().forEach { room -> onRoomSeen(client, room, selfMxid) }
            requestInviteRefresh()
            attachRoomList(client, selfMxid)

            // A logout (or an account switch) needs fresh listeners on the next start.
            session.state.collect { state ->
                if (state is SocialSessionState.Unauthenticated) stop()
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        listenerJobs.values.forEach { it.cancel() }
        listenerJobs.clear()
        runCatching { roomListTask?.cancel() }
        runCatching { roomListResult?.close() }
        roomListTask = null
        roomListResult = null
        attachedClient = null
        scope.coroutineContext.cancelChildren()
    }

    private fun markActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    private fun requestInviteRefresh() {
        inviteRefreshRequests.tryEmit(Unit)
    }

    private suspend fun attachRoomList(client: Client, selfMxid: String) {
        val roomListService = awaitRoomListService() ?: run {
            logcat(LogPriority.WARN) { "room list service unavailable; new rooms will not be observed" }
            return
        }
        try {
            val listener = object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    markActivity()
                    scope.launch {
                        roomEntriesUpdate.flatMap(::roomsFromUpdate).forEach { room ->
                            onRoomSeen(client, room, selfMxid)
                        }
                    }
                }
            }
            val result = roomListService.allRooms().entriesWithDynamicAdapters(PAGE_SIZE, listener)
            roomListResult = result
            // Joined rooms for messages, invited rooms for friend requests.
            result.controller().setFilter(RoomListEntriesDynamicFilterKind.NonLeft)
            result.controller().addOnePage()
            roomListTask = result.entriesStream()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "room list listener failed" }
        }
    }

    private suspend fun awaitRoomListService(): RoomListService? {
        repeat(50) {
            session.roomListServiceOrNull()?.let { return it }
            delay(100)
        }
        return null
    }

    private fun roomsFromUpdate(update: RoomListEntriesUpdate): List<Room> = when (update) {
        is RoomListEntriesUpdate.PushBack -> listOf(update.value)
        is RoomListEntriesUpdate.PushFront -> listOf(update.value)
        is RoomListEntriesUpdate.Insert -> listOf(update.value)
        is RoomListEntriesUpdate.Set -> listOf(update.value)
        is RoomListEntriesUpdate.Append -> update.values
        is RoomListEntriesUpdate.Reset -> update.values
        else -> emptyList()
    }

    private suspend fun onRoomSeen(client: Client, room: Room, selfMxid: String) {
        when (runCatching { room.membership() }.getOrNull()) {
            Membership.INVITED -> requestInviteRefresh()
            Membership.JOINED -> attachRoomListener(client, room.id(), selfMxid)
            else -> Unit
        }
    }

    private suspend fun attachRoomListener(client: Client, roomId: String, selfMxid: String) {
        if (listenerJobs.containsKey(roomId)) return
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return
        // Shiro work rooms notify messages; activity rooms feed the Social page.
        val isActivityRoom = activity.isActivityRoom(room)
        if (!isActivityRoom && SocialRoomKey.parseTopic(room.topic().orEmpty()) == null) return

        val timeline: Timeline = try {
            room.timelineWithConfiguration(
                TimelineConfiguration(
                    focus = TimelineFocus.Live(hideThreadedEvents = false),
                    filter = TimelineFilter.All,
                    internalIdPrefix = "",
                    dateDividerMode = DateDividerMode.DAILY,
                    trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                    reportUtds = false,
                ),
            )
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "timeline unavailable for $roomId" }
            return
        }

        val listener = object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                markActivity()
                diff.flatMap(::itemsFromDiff).forEach { item ->
                    if (isActivityRoom) activity.onTimelineItem(room, item) else handleItem(room, selfMxid, item)
                }
            }
        }
        val job = scope.launch {
            var handle: TaskHandle? = null
            try {
                handle = timeline.addListener(listener)
                awaitCancellation()
            } finally {
                runCatching { handle?.cancel() }
                runCatching { timeline.close() }
                listenerJobs.remove(roomId)
            }
        }
        if (listenerJobs.putIfAbsent(roomId, job) != null) job.cancel()
    }

    /**
     * Every diff that carries items. `Reset` delivers what the cache already holds, `Set`
     * delivers a message once decrypted: the cursor keeps both from notifying twice.
     */
    private fun itemsFromDiff(diff: TimelineDiff): List<TimelineItem> = when (diff) {
        is TimelineDiff.PushBack -> listOf(diff.value)
        is TimelineDiff.PushFront -> listOf(diff.value)
        is TimelineDiff.Insert -> listOf(diff.value)
        is TimelineDiff.Set -> listOf(diff.value)
        is TimelineDiff.Append -> diff.values
        is TimelineDiff.Reset -> diff.values
        else -> emptyList()
    }

    private fun handleItem(room: Room, selfMxid: String, item: TimelineItem) {
        val event = item.asEvent() ?: return
        if (event.sender == selfMxid) return
        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return
        val msgLike = event.content as? TimelineItemContent.MsgLike ?: return
        val body = when (val kind = msgLike.content.kind) {
            is MsgLikeKind.Message -> kind.content.body
            is MsgLikeKind.Sticker -> kind.body
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return

        scope.launch {
            notifier.onMessage(
                roomId = room.id(),
                eventId = eventId,
                timestamp = event.timestamp.toLong(),
                senderMxid = event.sender,
                senderDisplayName = runCatching { room.memberDisplayName(event.sender) }.getOrNull(),
                body = body,
                selfMxid = selfMxid,
                roomTopic = room.topic(),
                roomDisplayName = room.displayName(),
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 200u
        const val INVITE_REFRESH_DEBOUNCE_MS = 500L
        const val RESTORE_RETRY_MIN_MS = 15_000L
        const val RESTORE_RETRY_MAX_MS = 5 * 60_000L
    }
}
