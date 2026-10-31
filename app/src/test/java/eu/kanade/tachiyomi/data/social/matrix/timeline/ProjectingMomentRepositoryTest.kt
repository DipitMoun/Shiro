package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadTimeline
import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TimelineStatus
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import eu.kanade.tachiyomi.data.social.matrix.room.InMemoryRoomBindingStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectingMomentRepositoryTest {

    private lateinit var source: FakeThreadTimelineSource
    private lateinit var bindings: InMemoryRoomBindingStore
    private lateinit var repo: ProjectingMomentRepository

    private val workId = WorkId.parse("anilist:154587")!!
    private val episodeKey = EpisodeKey.parse("S01E003")!!
    private val thread = ThreadRef(
        authorMxid = "@alice:example.org",
        workId = workId,
        episodeKey = episodeKey,
    )
    private val roomKey = SocialRoomKey("@alice:example.org", workId)
    private val threadKey = SocialThreadKey(roomKey, episodeKey)

    private val rootId = "\$root"

    @BeforeEach
    fun setUp() = runBlocking {
        source = FakeThreadTimelineSource()
        bindings = InMemoryRoomBindingStore()
        bindings.bindRoom(roomKey, "!room:example.org")
        bindings.bindThreadRoot(threadKey, rootId)
        repo = ProjectingMomentRepository(source, bindings)
    }

    private fun momentEvent(id: String, ts: Long, sender: String = "@bob:example.org"): TransportEvent =
        TransportEvent(
            eventId = id,
            roomId = "!room:example.org",
            sender = sender,
            type = "m.room.message",
            originServerTs = ts,
            contentJson = """{
                "msgtype": "m.text",
                "body": "fallback body",
                "org.shiro.moment": {
                    "v": 1,
                    "work": {"id": "anilist:154587"},
                    "episode": {"key": "S01E003"},
                    "moments": [{"positionMs": 100000}],
                    "source": {"key": "aniyomi:123"},
                    "capturedAt": "2026-10-31T10:00:00Z"
                }
            }""",
        )

    private fun replyEvent(id: String, ts: Long, body: String = "hello"): TransportEvent =
        TransportEvent(
            eventId = id,
            roomId = "!room:example.org",
            sender = "@chloe:example.org",
            type = "m.room.message",
            originServerTs = ts,
            contentJson = """{"msgtype": "m.text", "body": "$body"}""",
        )

    private fun reactionEvent(id: String, targetId: String, key: String): TransportEvent =
        TransportEvent(
            eventId = id,
            roomId = "!room:example.org",
            sender = "@dave:example.org",
            type = "m.reaction",
            originServerTs = 1_000L,
            contentJson = """{"m.relates_to":{"rel_type":"m.annotation","event_id":"$targetId","key":"$key"}}""",
        )

    private fun pinsEvent(ids: List<String>): TransportEvent {
        val joined = ids.joinToString(",") { "\"$it\"" }
        return TransportEvent(
            eventId = "\$pins1",
            roomId = "!room:example.org",
            sender = "@alice:example.org",
            type = "org.shiro.pins",
            originServerTs = 2_000L,
            contentJson = """{"v":1,"threadRoot":"$rootId","eventIds":[$joined]}""",
        )
    }

    @Test
    fun `unbound thread emits Auth failure once`() = runBlocking {
        val unbound = ThreadRef("@unknown:example.org", workId, episodeKey)
        val first = withTimeout(2000) { repo.observeThread(unbound).first() }
        assertTrue(first is SocialResult.Failure)
    }

    @Test
    fun `initial emission is empty SYNCING timeline`() = runBlocking {
        val first = withTimeout(2000) { repo.observeThread(thread).first() }
        assertTrue(first is SocialResult.Success)
        val t = (first as SocialResult.Success).value
        assertEquals(TimelineStatus.SYNCING, t.status)
        assertTrue(t.items.isEmpty())
        assertEquals("!room:example.org", t.roomId)
        assertEquals(rootId, t.threadRootEventId)
    }

    @Test
    fun `moment event becomes MomentItem with LIVE status`() = runBlocking {
        source.setEvents(thread, listOf(momentEvent("\$m1", 500L)))
        val emissions = withTimeout(3000) { repo.observeThread(thread).take(2).toList() }
        val last = (emissions.last() as SocialResult.Success).value
        assertEquals(TimelineStatus.LIVE, last.status)
        assertEquals(1, last.items.size)
        assertTrue(last.items[0] is SocialTimelineItem.MomentItem)
        assertEquals("@bob:example.org", last.items[0].sender)
    }

    @Test
    fun `plain message becomes ReplyItem`() = runBlocking {
        source.setEvents(thread, listOf(replyEvent("\$r1", 100L, "salut")))
        val emissions = withTimeout(3000) { repo.observeThread(thread).take(2).toList() }
        val last = (emissions.last() as SocialResult.Success).value
        assertTrue(last.items[0] is SocialTimelineItem.ReplyItem)
        assertEquals("salut", (last.items[0] as SocialTimelineItem.ReplyItem).body)
    }

    @Test
    fun `reaction is attached to target moment`() = runBlocking {
        source.setEvents(
            thread,
            listOf(momentEvent("\$m1", 500L), reactionEvent("\$rx1", "\$m1", "fire")),
        )
        val emissions = withTimeout(3000) { repo.observeThread(thread).take(2).toList() }
        val last = (emissions.last() as SocialResult.Success).value
        val item = last.items.first { it.eventId == "\$m1" }
        assertEquals(1, item.reactions.size)
        assertEquals("fire", item.reactions[0].key)
        assertEquals(1, item.reactions[0].count)
    }

    @Test
    fun `pins mark referenced moment as pinned`() = runBlocking {
        source.setEvents(
            thread,
            listOf(
                momentEvent("\$m1", 500L),
                momentEvent("\$m2", 800L),
                pinsEvent(listOf("\$m2")),
            ),
        )
        val emissions = withTimeout(3000) { repo.observeThread(thread).take(2).toList() }
        val last = (emissions.last() as SocialResult.Success).value
        val m1 = last.items.first { it.eventId == "\$m1" } as SocialTimelineItem.MomentItem
        val m2 = last.items.first { it.eventId == "\$m2" } as SocialTimelineItem.MomentItem
        assertEquals(false, m1.pinned)
        assertEquals(true, m2.pinned)
    }

    @Test
    fun `redacted moment is removed from timeline`() = runBlocking {
        val active = momentEvent("\$m1", 500L)
        val redacted = active.copy(redactedByEventId = "\$redact1")
        source.setEvents(thread, listOf(active))
        source.setEvents(thread, listOf(redacted))
        val emissions = withTimeout(3000) { repo.observeThread(thread).take(2).toList() }
        val last = (emissions.last() as SocialResult.Success).value
        assertTrue(last.items.isEmpty())
    }

    @Test
    fun `pageBack delegates to source and returns projected items`() = runBlocking {
        source.pageBackResult = SocialResult.Success(
            TimelinePageResult(
                events = listOf(replyEvent("\$old1", 100L, "ancient")),
                nextCursor = "cursor-2",
            ),
        )
        val page = repo.pageBack(thread, "cursor-1")
        assertTrue(page is SocialResult.Success)
        val result = (page as SocialResult.Success).value
        assertEquals(1, result.items.size)
        assertEquals("ancient", (result.items[0] as SocialTimelineItem.ReplyItem).body)
        assertEquals("cursor-2", result.nextCursor)
        assertEquals(1, source.pageBackCalls)
    }
}