package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import kotlinx.coroutines.flow.Flow

/**
 * P2-07: SDK-free source of timeline events for one thread.
 *
 * The production implementation wraps `Room.timelineWithConfiguration` +
 * `Timeline.addListener`; tests use [FakeThreadTimelineSource].
 *
 * Each emission is a batch of new or updated [TransportEvent]s (reactions,
 * edits, redactions included). Duplicate events are tolerated: the projecting
 * repository processes them idempotently.
 */
interface ThreadTimelineSource {

    fun observeThread(
        thread: ThreadRef,
        roomId: String,
        threadRootEventId: String,
    ): Flow<SocialResult<List<TransportEvent>>>

    suspend fun pageBack(
        thread: ThreadRef,
        roomId: String,
        threadRootEventId: String,
        cursor: String,
        limit: Int,
    ): SocialResult<TimelinePageResult>
}

data class TimelinePageResult(
    val events: List<TransportEvent>,
    val nextCursor: String?,
)