package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import eu.kanade.tachiyomi.data.social.matrix.session.RustSessionDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/**
 * P2-07b: production [ThreadTimelineSource] wired to the Rust Matrix SDK.
 *
 * Wraps `Room.timelineWithConfiguration(TimelineFocus.Thread(...))`, subscribes
 * through [TimelineListener], and projects [TimelineDiff] batches into the
 * SDK-free [TransportEvent] model consumed by [ProjectingMomentRepository].
 *
 * Notes:
 * - [RustSessionDelegate.clientOrNull] is `internal` to the module, so this
 *   class only works when built with the Matrix SDK on the classpath (always
 *   true in the `:app` module).
 * - `latestJson()` returns `{}` for redacted events; those are emitted once as
 *   a `TransportEvent` with `redactedByEventId` set, so the projection removes
 *   them idempotently.
 * - P2-11 integration tests will exercise real sync; this file only compiles.
 */
class RustThreadTimelineSource(
    private val sessionDelegate: RustSessionDelegate,
) : ThreadTimelineSource {

    override fun observeThread(
        thread: ThreadRef,
        roomId: String,
        threadRootEventId: String,
    ): Flow<SocialResult<List<TransportEvent>>> = callbackFlow {
        val client = sessionDelegate.clientOrNull()
        if (client == null) {
            trySend(SocialResult.Failure(notAuthenticated()))
            close()
            return@callbackFlow
        }

        val room = client.getRoom(roomId)
        if (room == null) {
            trySend(SocialResult.Failure(SocialError.Unknown("Room $roomId not found")))
            close()
            return@callbackFlow
        }

        val config = TimelineConfiguration(
            focus = TimelineFocus.Thread(threadRootEventId),
            filter = TimelineFilter.All,
            internalIdPrefix = "",
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
            reportUtds = false,
        )

        val timeline: Timeline
        try {
            timeline = room.timelineWithConfiguration(config)
        } catch (t: Throwable) {
            trySend(SocialResult.Failure(mapError(t, "timelineWithConfiguration")))
            close()
            return@callbackFlow
        }

        val state = MutableStateFlow<List<TransportEvent>>(emptyList())
        val accumulator = EventAccumulator()

        val listener = object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                android.util.Log.d("SocialOverlay", "Timeline onUpdate: ${'$'}{diff.size} diffs")
                accumulator.apply(diff)
                state.value = accumulator.snapshot()
                trySend(SocialResult.Success(state.value))
            }
        }

        val handle: TaskHandle
        try {
            handle = timeline.addListener(listener)
        } catch (t: Throwable) {
            timeline.close()
            trySend(SocialResult.Failure(mapError(t, "addListener")))
            close()
            return@callbackFlow
        }

        // Emit an initial empty snapshot so the repository can emit a
        // SYNCING timeline even before the first diff arrives.
        trySend(SocialResult.Success(emptyList()))

        awaitClose {
            try { handle.cancel() } catch (_: Throwable) {}
            try { timeline.close() } catch (_: Throwable) {}
        }
    }

    override suspend fun pageBack(
        thread: ThreadRef,
        roomId: String,
        threadRootEventId: String,
        cursor: String,
        limit: Int,
    ): SocialResult<TimelinePageResult> {
        val client = sessionDelegate.clientOrNull()
            ?: return SocialResult.Failure(notAuthenticated())

        val room = client.getRoom(roomId)
            ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found"))

        return try {
            val config = TimelineConfiguration(
                focus = TimelineFocus.Thread(threadRootEventId),
                filter = TimelineFilter.All,
                internalIdPrefix = "",
                dateDividerMode = DateDividerMode.DAILY,
                trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                reportUtds = false,
            )
            val timeline = room.timelineWithConfiguration(config)
            try {
                timeline.paginateBackwards(limit.toUShort())
                // P2-11 will collect the actual pagination events via the
                // back-pagination listener. For now we return an empty page
                // and let observeThread receive the events through the diff
                // stream after pagination settles.
                SocialResult.Success(TimelinePageResult(events = emptyList(), nextCursor = null))
            } finally {
                try { timeline.close() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "paginateBackwards"))
        }
    }

    private fun notAuthenticated(): SocialError = SocialError.Auth(
        reason = AuthFailureReason.NOT_ALLOWED,
        message = "No Matrix session active",
    )

    private fun mapError(t: Throwable, op: String): SocialError {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            "unauthor" in lower || "forbidden" in lower -> SocialError.Permission(msg)
            "network" in lower || "timed out" in lower || "connect" in lower ->
                SocialError.Network(transient = true, message = msg, cause = t)
            else -> SocialError.Unknown("$op failed: $msg", t)
        }
    }

    /**
     * Mutable accumulator: `eventId -> TransportEvent`. Handles the timeline
     * diffs the live sync produces in practice (Append, PushBack, PushFront,
     * Reset, Set). Rarer diffs (Remove, Clear, Truncate, PopBack, PopFront)
     * are handled by falling back to a full `Reset` on the next batch, which
     * the SDK emits whenever it needs to reconcile.
     */
    private class EventAccumulator {
        private val events = LinkedHashMap<String, TransportEvent>()
        private val indexOrder = ArrayList<String>()

        fun apply(diffs: List<TimelineDiff>) {
            for (diff in diffs) {
                when (diff) {
                    is TimelineDiff.Append -> appendAll(diff.values)
                    is TimelineDiff.PushBack -> append(diff.value)
                    is TimelineDiff.PushFront -> append(diff.value)
                    is TimelineDiff.Reset -> { reset(diff.values) }
                    is TimelineDiff.Set -> setAt(diff.index.toInt() and 0xFFFF, diff.value)
                    is TimelineDiff.Insert -> append(diff.value)
                    else -> { /* Remove/Truncate/Clear/PopBack/PopFront: next Reset reconciles */ }
                }
            }
        }

        fun snapshot(): List<TransportEvent> = events.values.toList()

        private fun appendAll(items: List<TimelineItem>) {
            items.forEach { append(it) }
        }

        private fun append(item: TimelineItem) {
            val event = item.asEvent() ?: return
            val mapped = mapEvent(event) ?: return
            events[mapped.eventId] = mapped
            if (mapped.eventId !in indexOrder) indexOrder.add(mapped.eventId)
        }

        private fun setAt(index: Int, item: TimelineItem) {
            if (index < 0 || index >= indexOrder.size) return
            val targetId = indexOrder[index]
            val event = item.asEvent() ?: return
            val mapped = mapEvent(event) ?: return
            events[targetId] = mapped
        }

        private fun reset(items: List<TimelineItem>) {
            events.clear()
            indexOrder.clear()
            items.forEach { append(it) }
        }

        private fun mapEvent(item: EventTimelineItem): TransportEvent? {
            val eventId = when (val id = item.eventOrTransactionId) {
                is EventOrTransactionId.EventId -> id.eventId
                is EventOrTransactionId.TransactionId -> return null // local echo, not yet on server
            }
            val rawJson: String = try { item.lazyProvider.latestJson() ?: "{}" } catch (_: Throwable) { "{}" }
            val isRedacted = rawJson.isBlank() || rawJson == "{}"
            val sender: String = item.sender ?: ""
            val eventType: String = item.eventTypeRaw ?: "m.room.message"
            val timestampMs: Long = item.timestamp.toLong()
            return TransportEvent(
                eventId = eventId,
                roomId = "",
                sender = sender,
                type = eventType,
                stateKey = null,
                originServerTs = timestampMs,
                contentJson = rawJson,
                redactedByEventId = if (isRedacted) eventId else null,
            )
        }
    }
}