package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Scriptable [ThreadTimelineSource] for tests.
 *
 * Emits the full current batch on every [setEvents] call, so the projecting
 * repository must be idempotent (it is).
 */
class FakeThreadTimelineSource : ThreadTimelineSource {

    private val batches = mutableMapOf<String, MutableStateFlow<List<TransportEvent>>>()

    var pageBackResult: SocialResult<TimelinePageResult> =
        SocialResult.Success(TimelinePageResult(emptyList(), null))
    var pageBackCalls: Int = 0; private set

    override fun observeThread(
        thread: ThreadRef,
        roomId: String,
        threadRootEventId: String,
    ): Flow<SocialResult<List<TransportEvent>>> {
        val flow = batches.getOrPut(thread.key()) { MutableStateFlow(emptyList()) }
        return flow.map { SocialResult.Success(it) }
    }

    fun setEvents(thread: ThreadRef, events: List<TransportEvent>) {
        batches.getOrPut(thread.key()) { MutableStateFlow(emptyList()) }.value = events
    }

    override suspend fun pageBack(
        thread: ThreadRef,
        roomId: String,
        threadRootEventId: String,
        cursor: String,
        limit: Int,
    ): SocialResult<TimelinePageResult> {
        pageBackCalls++
        return pageBackResult
    }

    private fun ThreadRef.key(): String =
        "${authorMxid}/${workId.value}/${episodeKey.value}"
}