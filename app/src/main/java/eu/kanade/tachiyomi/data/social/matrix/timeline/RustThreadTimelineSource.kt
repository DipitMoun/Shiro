package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import eu.kanade.tachiyomi.data.social.matrix.TransportReaction
import eu.kanade.tachiyomi.data.social.matrix.session.RustSessionDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Wraps `Room.timelineWithConfiguration(TimelineFocus.Live(...))`, filters
 * events by their `m.thread` relation to the episode root, subscribes through
 * [TimelineListener], and projects [TimelineDiff] batches into the SDK-free
 * [TransportEvent] model consumed by [ProjectingMomentRepository].
 *
 * Notes:
 * - [RustSessionDelegate.clientOrNull] is `internal` to the module, so this
 *   class only works when built with the Matrix SDK on the classpath (always
 *   true in the `:app` module).
 * - `latestJson()` returns the full event envelope; the projection only receives
 *   its `content` object.
 * - Redacted events are emitted once with `redactedByEventId` set, so the
 *   projection removes them idempotently.
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
            // Thread focus is not reliable for custom `org.shiro.thread`
            // roots in the current SDK build. Read the room's live timeline and
            // filter replies by their thread relation instead.
            focus = TimelineFocus.Live(hideThreadedEvents = false),
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
        val accumulator = EventAccumulator(threadRootEventId)

        val listener = object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
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

        // Do not clobber the listener's initial snapshot with an empty list.
        // Force one initial back-pagination so an existing thread is hydrated
        // even when the listener only starts with an incremental diff.
        try {
            timeline.paginateBackwards(30.toUShort())
        } catch (t: Throwable) {
            android.util.Log.w("SocialOverlaySync", "initial timeline hydration failed", t)
        }

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
                focus = TimelineFocus.Live(hideThreadedEvents = false),
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
    private class EventAccumulator(private val threadRootEventId: String) {
        private val events = LinkedHashMap<String, TransportEvent>()
        // The SDK diff indices refer to the full room timeline, not to the
        // reduced thread view. Keep every index aligned, even when an item is
        // filtered out, otherwise a Set/Insert reaction update targets the
        // wrong message or is dropped.
        private val allOrder = ArrayList<String>()
        private var virtualCounter = 0L

        fun apply(diffs: List<TimelineDiff>) {
            for (diff in diffs) {
                when (diff) {
                    is TimelineDiff.Append -> appendAll(diff.values)
                    is TimelineDiff.PushBack -> append(diff.value, atStart = false)
                    is TimelineDiff.PushFront -> append(diff.value, atStart = true)
                    is TimelineDiff.Reset -> reset(diff.values)
                    is TimelineDiff.Set -> setAt(diff.index.toInt() and 0xFFFF, diff.value)
                    is TimelineDiff.Insert -> insertAt(diff.index.toInt() and 0xFFFF, diff.value)
                    is TimelineDiff.Remove -> removeAt(diff.index.toInt() and 0xFFFF)
                    is TimelineDiff.Truncate -> truncate(diff.length.toInt() and 0xFFFF)
                    is TimelineDiff.Clear -> clearAll()
                    is TimelineDiff.PopBack -> removeAt(allOrder.lastIndex)
                    is TimelineDiff.PopFront -> removeAt(0)
                }
            }
        }

        fun snapshot(): List<TransportEvent> = events.values.toList()

        private fun appendAll(items: List<TimelineItem>) {
            items.forEach { append(it, atStart = false) }
        }

        private fun append(item: TimelineItem, atStart: Boolean) {
            val id = itemKey(item)
            if (atStart) allOrder.add(0, id) else allOrder.add(id)
            addMapped(item)
        }

        private fun insertAt(index: Int, item: TimelineItem) {
            if (index !in 0..allOrder.size) return
            allOrder.add(index, itemKey(item))
            addMapped(item)
        }

        private fun setAt(index: Int, item: TimelineItem) {
            if (index !in allOrder.indices) return
            val oldId = allOrder[index]
            events.remove(oldId)
            val newId = itemKey(item)
            allOrder[index] = newId
            addMapped(item)
        }

        private fun removeAt(index: Int) {
            if (index !in allOrder.indices) return
            events.remove(allOrder.removeAt(index))
        }

        private fun truncate(length: Int) {
            if (length >= allOrder.size) return
            while (allOrder.size > length) {
                events.remove(allOrder.removeAt(allOrder.lastIndex))
            }
        }

        private fun clearAll() {
            events.clear()
            allOrder.clear()
        }

        private fun reset(items: List<TimelineItem>) {
            clearAll()
            items.forEach { append(it, atStart = false) }
        }

        private fun itemKey(item: TimelineItem): String {
            val id = item.asEvent()?.eventOrTransactionId
            return when (id) {
                is EventOrTransactionId.EventId -> id.eventId
                is EventOrTransactionId.TransactionId -> "tx:${id.transactionId}"
                null -> "virtual:${virtualCounter++}"
            }
        }

        private fun addMapped(item: TimelineItem) {
            val event = item.asEvent() ?: return
            val mapped = mapEvent(event) ?: return
            events[mapped.eventId] = mapped
        }

        private fun belongsToObservedThread(eventId: String, type: String, rawJson: String): Boolean {
            if (type != "m.room.message") return true
            if (eventId == threadRootEventId) return true
            val relatedEventId = runCatching {
                json.parseToJsonElement(eventContent(rawJson))
                    .jsonObject["m.relates_to"]
                    ?.jsonObject
                    ?.get("event_id")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
            return relatedEventId == threadRootEventId
        }

        /**
         * `latestJson()` returns the whole Matrix event
         * (`{"event_id":...,"content":{...}}`). The projection layer expects
         * only the content object.
         */
        private fun eventContent(rawJson: String): String =
            runCatching {
                val event = json.parseToJsonElement(rawJson).jsonObject
                event["content"]?.jsonObject?.toString() ?: rawJson
            }.getOrDefault(rawJson)

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
            if (!belongsToObservedThread(eventId, eventType, rawJson)) return null
            return TransportEvent(
                eventId = eventId,
                roomId = "",
                sender = sender,
                type = eventType,
                stateKey = null,
                originServerTs = timestampMs,
                contentJson = eventContent(rawJson),
                redactedByEventId = if (isRedacted) eventId else null,
                reactions = item.reactions.flatMap { reaction ->
                    reaction.senders.map { sender -> TransportReaction(reaction.key, sender.senderId) }
                },
            )
        }
    }
}