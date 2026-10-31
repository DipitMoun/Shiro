package eu.kanade.tachiyomi.ui.social.overlay

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadTimeline
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.TimelinePage
import eu.kanade.tachiyomi.data.social.matrix.TimelineStatus
import eu.kanade.tachiyomi.data.social.matrix.command.InMemorySocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.push.InMemoryPushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialOverlayControllerTest {

    private val thread = ThreadRef(
        authorMxid = "@alice:example.org",
        workId = WorkId.parse("anilist:154587")!!,
        episodeKey = EpisodeKey.parse("S01E003")!!,
    )
    private val account = SocialAccount(
        userId = "@alice:example.org",
        deviceId = "DEV1",
        homeserverUrl = "https://matrix.org",
        displayName = "Alice",
    )

    private class FakeSession(initial: SocialSessionState) : MatrixSessionManager {
        private val flow = MutableStateFlow(initial)
        override val state: StateFlow<SocialSessionState> = flow
        override val account: StateFlow<SocialAccount?> = MutableStateFlow(null)
        override suspend fun login(homeserverUrl: String, userId: String, password: String, deviceDisplayName: String?) =
            SocialResult.Failure(SocialError.Auth(AuthFailureReason.UNKNOWN))
        override suspend fun restore(): SocialResult<SocialAccount?> = SocialResult.Success(null)
        override suspend fun logout(): SocialResult<Unit> = SocialResult.Success(Unit)
        override fun transportOrNull(): MatrixTransport? = null
        fun set(s: SocialSessionState) { flow.value = s }
    }

    private class FakeTimelineRepo : SocialThreadRepository {
        var result: SocialResult<SocialThreadTimeline> = SocialResult.Failure(SocialError.Unknown("empty"))
        override fun observeThread(thread: ThreadRef): Flow<SocialResult<SocialThreadTimeline>> = flowOf(result)
        override suspend fun pageBack(thread: ThreadRef, cursor: String): SocialResult<TimelinePage> =
            SocialResult.Success(TimelinePage(emptyList(), null))
    }

    @Test
    fun `initial state is closed and disconnected`() {
        val c = SocialOverlayController(
            FakeSession(SocialSessionState.Unauthenticated),
            FakeTimelineRepo(),
            InMemorySocialCommandDispatcher(),
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        assertTrue(!c.state.value.isOpen)
        assertTrue(!c.state.value.isConnected)
        assertNull(c.state.value.thread)
    }

    @Test
    fun `connect reflects authenticated session`() = runBlocking {
        val session = FakeSession(SocialSessionState.Unauthenticated)
        val c = SocialOverlayController(
            session,
            FakeTimelineRepo(),
            InMemorySocialCommandDispatcher(),
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.connect()
        assertTrue(!c.state.value.isConnected)
        session.set(SocialSessionState.Authenticated(account))
        assertTrue(c.state.value.isConnected)
        assertEquals("@alice:example.org", c.state.value.account?.userId)
    }

    @Test
    fun `open sets thread and observes timeline`() {
        val repo = FakeTimelineRepo()
        repo.result = SocialResult.Success(
            SocialThreadTimeline(
                thread = thread,
                roomId = "!room:example.org",
                threadRootEventId = "\$root",
                items = emptyList(),
                status = TimelineStatus.SYNCING,
                olderCursor = null,
            ),
        )
        val c = SocialOverlayController(
            FakeSession(SocialSessionState.Unauthenticated),
            repo,
            InMemorySocialCommandDispatcher(),
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.open(thread)
        assertTrue(c.state.value.isOpen)
        assertNotNull(c.state.value.thread)
        assertNotNull(c.state.value.timeline)
    }

    @Test
    fun `close keeps thread but clears isOpen`() {
        val c = SocialOverlayController(
            FakeSession(SocialSessionState.Unauthenticated),
            FakeTimelineRepo(),
            InMemorySocialCommandDispatcher(),
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.open(thread)
        c.close()
        assertTrue(!c.state.value.isOpen)
    }

    @Test
    fun `reply fails when disconnected`() = runBlocking {
        val dispatcher = InMemorySocialCommandDispatcher()
        val c = SocialOverlayController(
            FakeSession(SocialSessionState.Unauthenticated),
            FakeTimelineRepo(),
            dispatcher,
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.open(thread)
        val result = c.reply("hello")
        assertTrue(result is SocialResult.Failure)
        assertEquals(0, dispatcher.executed.size)
    }

    @Test
    fun `reply dispatches when connected`() = runBlocking {
        val session = FakeSession(SocialSessionState.Authenticated(account))
        val dispatcher = InMemorySocialCommandDispatcher()
        val c = SocialOverlayController(
            session,
            FakeTimelineRepo(),
            dispatcher,
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.connect()
        c.open(thread)
        val result = c.reply("hello")
        assertTrue(result is SocialResult.Success)
        assertEquals(1, dispatcher.executed.size)
    }

    @Test
    fun `react dispatches with correct thread`() = runBlocking {
        val session = FakeSession(SocialSessionState.Authenticated(account))
        val dispatcher = InMemorySocialCommandDispatcher()
        val c = SocialOverlayController(
            session,
            FakeTimelineRepo(),
            dispatcher,
            InMemoryPushRegistrar(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.connect()
        c.open(thread)
        c.react("\$e1", "fire")
        assertEquals(1, dispatcher.executed.size)
    }

    @Test
    fun `registerPush delegates to registrar`() = runBlocking {
        val session = FakeSession(SocialSessionState.Authenticated(account))
        val push = InMemoryPushRegistrar()
        val c = SocialOverlayController(
            session,
            FakeTimelineRepo(),
            InMemorySocialCommandDispatcher(),
            push,
            CoroutineScope(Dispatchers.Unconfined),
        )
        c.registerPush("https://ntfy.sh/topic")
        assertEquals("https://ntfy.sh/topic", push.currentEndpoint)
    }
}
