package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.matrix.DeliveryState
import eu.kanade.tachiyomi.data.social.matrix.MomentPayload
import eu.kanade.tachiyomi.data.social.matrix.ReactionEntry
import eu.kanade.tachiyomi.data.social.matrix.ReactionSummary
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadTimeline
import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TimelinePage
import eu.kanade.tachiyomi.data.social.matrix.TimelineStatus
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import eu.kanade.tachiyomi.data.social.matrix.room.RoomBindingStore
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P2-07: SDK-free implementation of [SocialThreadRepository].
 *
 * Consumes [ThreadTimelineSource] events, aggregates reactions and pins, and
 * projects them into [SocialThreadTimeline]. State is local to each observation
 * flow instance; P2-11 hardening may add a shared cache.
 */
class ProjectingMomentRepository(
    private val source: ThreadTimelineSource,
    private val bindings: RoomBindingStore,
) : SocialThreadRepository {

    override fun observeThread(thread: ThreadRef): Flow<SocialResult<SocialThreadTimeline>> = flow {
        val roomId = resolveRoom(thread)
        val rootId = resolveRoot(thread)
        if (roomId == null || rootId == null) {
            emit(SocialResult.Failure(SocialError.Unknown("Thread not bound: ${thread.describe()}")))
            return@flow
        }

        val state = MutableThreadState()
        emit(SocialResult.Success(state.toTimeline(thread, roomId, rootId, TimelineStatus.SYNCING)))

        source.observeThread(thread, roomId, rootId).collect { result ->
            when (result) {
                is SocialResult.Success -> {
                    state.apply(result.value)
                    val status = if (state.hasAny()) TimelineStatus.LIVE else TimelineStatus.SYNCING
                    emit(SocialResult.Success(state.toTimeline(thread, roomId, rootId, status)))
                }
                is SocialResult.Failure -> {
                    if (result.error is SocialError.Auth) {
                        emit(SocialResult.Failure(result.error))
                    } else {
                        emit(
                            SocialResult.Success(
                                state.toTimeline(thread, roomId, rootId, TimelineStatus.OFFLINE),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun pageBack(thread: ThreadRef, cursor: String): SocialResult<TimelinePage> {
        val roomId = resolveRoom(thread)
        val rootId = resolveRoot(thread)
        if (roomId == null || rootId == null) {
            return SocialResult.Failure(SocialError.Unknown("Thread not bound: ${thread.describe()}"))
        }

        val page = source.pageBack(thread, roomId, rootId, cursor, PAGE_SIZE)
        if (page is SocialResult.Failure) return page
        val result = (page as SocialResult.Success).value

        val state = MutableThreadState()
        state.apply(result.events)
        return SocialResult.Success(
            TimelinePage(
                items = state.decoratedItems(),
                nextCursor = result.nextCursor,
            ),
        )
    }

    private suspend fun resolveRoom(thread: ThreadRef): String? {
        val key = SocialRoomKey(authorMxid = thread.authorMxid, workId = thread.workId)
        return (bindings.resolveRoom(key) as? SocialResult.Success)?.value
    }

    private suspend fun resolveRoot(thread: ThreadRef): String? {
        val roomKey = SocialRoomKey(authorMxid = thread.authorMxid, workId = thread.workId)
        val threadKey = SocialThreadKey(room = roomKey, episodeKey = thread.episodeKey)
        return (bindings.resolveThreadRoot(threadKey) as? SocialResult.Success)?.value
    }

    private fun ThreadRef.describe(): String =
        "${authorMxid}/${workId.value}/${episodeKey.value}"

    companion object {
        internal const val PAGE_SIZE = 30
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /**
     * In-memory accumulator: items keyed by event id, reactions and pins.
     * Idempotent: applying the same event twice yields the same state.
     */
    internal class MutableThreadState {
        private val items = LinkedHashMap<String, SocialTimelineItem>()
        private val reactions = mutableMapOf<String, MutableMap<String, MutableList<ReactionEntry>>>()
        private var pins: List<String> = emptyList()

        fun hasAny(): Boolean = items.isNotEmpty()

        fun apply(events: List<TransportEvent>): Boolean {
            var changed = false
            for (event in events) {
                when (event.type) {
                    "m.room.message" -> changed = applyMessage(event) || changed
                    "m.reaction" -> changed = applyReaction(event) || changed
                    "org.shiro.pins" -> changed = applyPins(event) || changed
                }
            }
            return changed
        }

        fun decoratedItems(): List<SocialTimelineItem> =
            items.values.sortedBy { it.sentAt }.map { decorate(it) }

        fun toTimeline(
            thread: ThreadRef,
            roomId: String,
            rootId: String,
            status: TimelineStatus,
        ): SocialThreadTimeline = SocialThreadTimeline(
            thread = thread,
            roomId = roomId,
            threadRootEventId = rootId,
            items = decoratedItems(),
            status = status,
            olderCursor = null,
        )

        private fun decorate(item: SocialTimelineItem): SocialTimelineItem {
            val rs = reactions[item.eventId]
                ?.map { (key, list) -> ReactionSummary(key, list.toList()) }
                ?: emptyList()
            val pinned = item.eventId in pins
            return when (item) {
                is SocialTimelineItem.MomentItem -> item.copy(reactions = rs, pinned = pinned)
                is SocialTimelineItem.ReplyItem -> item.copy(reactions = rs)
            }
        }

        private fun applyMessage(event: TransportEvent): Boolean {
            if (event.redactedByEventId != null) {
                return items.remove(event.eventId) != null
            }
            val content = parseObject(event.contentJson) ?: return false
            val projected = projectMessage(event, content) ?: return false
            items[event.eventId] = projected
            return true
        }

        private fun projectMessage(event: TransportEvent, content: JsonObject): SocialTimelineItem? {
            val momentJson = content["org.shiro.moment"] as? JsonObject
            if (momentJson != null) {
                val payload = parseMoment(momentJson) ?: return null
                return SocialTimelineItem.MomentItem(
                    eventId = event.eventId,
                    sender = event.sender,
                    sentAt = Instant.ofEpochMilli(event.originServerTs),
                    payload = payload,
                    edited = false,
                    pinned = false,
                    reactions = emptyList(),
                    deliveryState = DeliveryState.SENT,
                )
            }
            val body = (content["body"] as? JsonPrimitive)?.content ?: return null
            val inReplyTo = (content["m.relates_to"] as? JsonObject)
                ?.get("m.in_reply_to") as? JsonObject
            val inReplyToId = (inReplyTo?.get("event_id") as? JsonPrimitive)?.content
            return SocialTimelineItem.ReplyItem(
                eventId = event.eventId,
                sender = event.sender,
                sentAt = Instant.ofEpochMilli(event.originServerTs),
                body = body,
                inReplyToEventId = inReplyToId,
                reactions = emptyList(),
                deliveryState = DeliveryState.SENT,
            )
        }

        private fun parseMoment(obj: JsonObject): MomentPayload? =
            try {
                ProjectingMomentRepository.json.decodeFromString<MomentPayload>(obj.toString())
            } catch (t: Throwable) {
                null
            }

        private fun applyReaction(event: TransportEvent): Boolean {
            val content = parseObject(event.contentJson) ?: return false
            val rel = content["m.relates_to"] as? JsonObject ?: return false
            val target = (rel["event_id"] as? JsonPrimitive)?.content ?: return false
            val key = (rel["key"] as? JsonPrimitive)?.content ?: return false

            if (event.redactedByEventId != null) {
                val removed = reactions[target]?.get(key)?.removeAll { it.eventId == event.eventId } ?: false
                return removed
            }

            val bucket = reactions
                .getOrPut(target) { mutableMapOf() }
                .getOrPut(key) { mutableListOf() }
            bucket.removeAll { it.eventId == event.eventId }
            bucket.add(ReactionEntry(sender = event.sender, eventId = event.eventId))
            return true
        }

        private fun applyPins(event: TransportEvent): Boolean {
            if (event.redactedByEventId != null) return false
            val content = parseObject(event.contentJson) ?: return false
            val ids = (content["eventIds"] as? JsonArray) ?: return false
            val newPins = ids.mapNotNull { (it as? JsonPrimitive)?.content }
            val changed = newPins != pins
            pins = newPins
            return changed
        }

        private fun parseObject(raw: String): JsonObject? =
            try { ProjectingMomentRepository.json.parseToJsonElement(raw) as? JsonObject } catch (t: Throwable) { null }
    }
}