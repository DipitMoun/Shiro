package eu.kanade.tachiyomi.ui.social

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.matrix.DeliveryState
import eu.kanade.tachiyomi.data.social.matrix.ReactionEntry
import eu.kanade.tachiyomi.data.social.matrix.ReactionSummary
import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadTimeline
import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TimelineStatus
import eu.kanade.tachiyomi.ui.social.overlay.ConnectionState
import eu.kanade.tachiyomi.ui.social.overlay.SocialOverlayState
import eu.kanade.tachiyomi.ui.social.overlay.SocialThreadDescriptor
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialUiStateTest {

    private val context = SocialMomentContext(
        workTitle = "Frieren",
        episodeLabel = "S1 E12",
        positionMs = 300_000L,
        durationMs = 1_440_000L,
    )

    private fun state() = SocialUiState(context)

    @Test
    fun `selecting an author opens that author's thread`() {
        val state = state()

        state.selectAuthor("david")

        assertEquals("thread-david", state.selectedThreadId)
        assertEquals("david", state.selectedThread?.authorId)
        assertEquals("David", state.selectedAuthor?.name)
        assertEquals(0, state.unread["david"])
    }

    @Test
    fun `new chat selects the personal empty thread`() {
        val state = state()

        state.newChat()

        assertEquals("thread-me", state.selectedThreadId)
        assertEquals(SocialDemoData.ME, state.selectedAuthor?.id)
        assertTrue(state.selectedThread?.messages?.isEmpty() == true)
    }

    @Test
    fun `reply trims text and attaches the current anchor`() {
        val state = state()
        state.selectAuthor("kevin")
        state.selectAnchor(123_456L)

        state.send("  bonjour  ")

        val message = state.selectedThread?.messages?.last()
        assertEquals("bonjour", message?.text)
        assertEquals(123_456L, message?.positionMs)
        assertEquals(SocialDemoData.ME, message?.authorId)
    }

    @Test
    fun `publish targets the personal thread`() {
        val state = state()
        state.selectAuthor("allan")
        state.newChat()

        state.send("mon moment")

        assertEquals("thread-me", state.selectedThreadId)
        assertEquals("mon moment", state.selectedThread?.messages?.last()?.text)
    }

    @Test
    fun `send reports the target audience and anchor to Matrix`() {
        val state = state()
        var sent: Triple<String, Boolean, Long>? = null
        state.onSendMatrix = { text, publish, anchorMs -> sent = Triple(text, publish, anchorMs) }

        state.selectAuthor("kevin")
        state.selectAnchor(42_000L)
        state.send("salut")

        assertEquals(Triple("salut", false, 42_000L), sent)
    }

    @Test
    fun `reaction increments only the target message`() {
        val state = state()
        val message = state.threads
            .first { it.authorId == "kevin" }
            .messages
            .first { it.id == "kevin-2" }
        val before = message.reactions["😱"] ?: 0

        state.react("kevin-2", "😱")

        val after = state.threads
            .flatMap { it.messages }
            .first { it.id == "kevin-2" }
            .reactions["😱"]
        assertEquals(before + 1, after)
    }

    @Test
    fun `empty demo keeps only the personal message history`() {
        val state = state()

        state.reload(DemoMode.Empty)

        assertTrue(state.threads.filter { it.authorId != SocialDemoData.ME }.all { it.messages.isEmpty() })
        assertTrue(state.threads.firstOrNull { it.authorId == SocialDemoData.ME } != null)
    }

    @Test
    fun `loading demo clears content and selection`() {
        val state = state()

        state.reload(DemoMode.Loading)

        assertTrue(state.threads.isEmpty())
        assertNull(state.selectedThreadId)
        assertNull(state.selectedThread)
    }

    @Test
    fun `matrix state projects every author thread and selects a friend`() {
        val workId = WorkId.parse("anilist:154587")!!
        val episodeKey = EpisodeKey.parse("E003")!!
        val self = "@me:example.org"
        val friend = "@kevin:example.org"
        val selfRef = ThreadRef(self, workId, episodeKey)
        val friendRef = ThreadRef(friend, workId, episodeKey)
        val sentAt = Instant.parse("2026-11-02T12:00:00Z")
        val reaction = ReactionSummary(
            key = "🔥",
            reactions = listOf(ReactionEntry(friend, "$friend-reaction")),
        )
        val selfTimeline = SocialThreadTimeline(
            thread = selfRef,
            roomId = "!self:example.org",
            threadRootEventId = "$self-root",
            items = listOf(
                SocialTimelineItem.ReplyItem(
                    eventId = "$self-message",
                    sender = self,
                    sentAt = sentAt,
                    body = "mon moment",
                    inReplyToEventId = null,
                    reactions = emptyList(),
                    deliveryState = DeliveryState.SENT,
                ),
            ),
            status = TimelineStatus.LIVE,
        )
        val friendTimeline = SocialThreadTimeline(
            thread = friendRef,
            roomId = "!friend:example.org",
            threadRootEventId = "$friend-root",
            items = listOf(
                SocialTimelineItem.ReplyItem(
                    eventId = "$friend-message",
                    sender = friend,
                    sentAt = sentAt.plusSeconds(1),
                    body = "excellent",
                    inReplyToEventId = null,
                    reactions = listOf(reaction),
                    deliveryState = DeliveryState.SENT,
                ),
            ),
            status = TimelineStatus.LIVE,
        )
        val matrixState = SocialOverlayState(
            isOpen = true,
            connection = ConnectionState.Connected(
                SocialAccount(
                    userId = self,
                    deviceId = "DEVICE",
                    homeserverUrl = "https://matrix.example.org",
                    displayName = "Moi",
                ),
            ),
            threads = listOf(
                SocialThreadDescriptor(friendRef, "Kevin", isOwn = false),
                SocialThreadDescriptor(selfRef, "Moi", isOwn = true),
            ),
            thread = friendRef,
            timeline = friendTimeline,
            timelines = mapOf(selfRef to selfTimeline, friendRef to friendTimeline),
        )
        val state = state()
        state.applyMatrixState(matrixState)

        assertEquals(self, state.selfMxid)
        assertEquals(2, state.threads.size)
        assertEquals(friend, state.selectedThread?.authorId)
        assertEquals("Kevin", state.selectedAuthor?.name)
        assertEquals("excellent", state.selectedThread?.messages?.single()?.text)
        assertEquals(1, state.selectedThread?.messages?.single()?.reactions?.get("🔥"))
        assertEquals("mon moment", state.threads.first { it.authorId == self }.messages.single().text)
    }

    @Test
    fun `matrix send is echoed locally before the server timeline catches up`() {
        val workId = WorkId.parse("anilist:154587")!!
        val episodeKey = EpisodeKey.parse("E003")!!
        val self = "@me:example.org"
        val friend = "@kevin:example.org"
        val friendRef = ThreadRef(friend, workId, episodeKey)
        val timeline = SocialThreadTimeline(
            thread = friendRef,
            roomId = "!friend:example.org",
            threadRootEventId = "$friend-root",
            items = emptyList(),
            status = TimelineStatus.LIVE,
        )
        val state = state()
        state.applyMatrixState(
            SocialOverlayState(
                isOpen = true,
                connection = ConnectionState.Connected(
                    SocialAccount(self, "DEVICE", "https://matrix.example.org"),
                ),
                threads = listOf(SocialThreadDescriptor(friendRef, "Kevin", isOwn = false)),
                thread = friendRef,
                timeline = timeline,
                timelines = mapOf(friendRef to timeline),
            ),
        )
        state.onSendMatrix = { _, _, _ -> }

        state.send("  salut  ")

        assertEquals("salut", state.selectedThread?.messages?.last()?.text)
        assertEquals(self, state.selectedThread?.messages?.last()?.authorId)
    }

    @Test
    fun `selecting a matrix author forwards the thread ref`() {
        val workId = WorkId.parse("anilist:154587")!!
        val episodeKey = EpisodeKey.parse("E003")!!
        val self = "@me:example.org"
        val friend = "@kevin:example.org"
        val friendRef = ThreadRef(friend, workId, episodeKey)
        val matrixState = SocialOverlayState(
            isOpen = true,
            connection = ConnectionState.Connected(
                SocialAccount(self, "DEVICE", "https://matrix.example.org"),
            ),
            threads = listOf(
                SocialThreadDescriptor(friendRef, "Kevin", isOwn = false),
                SocialThreadDescriptor(ThreadRef(self, workId, episodeKey), "Moi", isOwn = true),
            ),
            thread = friendRef,
            timelines = mapOf(
                friendRef to SocialThreadTimeline(
                    thread = friendRef,
                    roomId = "!friend:example.org",
                    threadRootEventId = "$friend-root",
                    items = emptyList(),
                    status = TimelineStatus.LIVE,
                ),
            ),
        )
        val state = state()
        state.applyMatrixState(matrixState)
        var selected: ThreadRef? = null
        state.onSelectThread = { selected = it }

        state.selectAuthor(friend)

        assertEquals(friendRef, selected)
    }
}
