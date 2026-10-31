package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.audience.SocialFriend
import eu.kanade.tachiyomi.data.social.identity.WorkFormat
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SocialRoomServiceTest {

    private lateinit var transport: FakeMatrixRoomTransport
    private lateinit var bindings: InMemoryRoomBindingStore
    private lateinit var service: DefaultSocialRoomService

    private val work = WorkIdentity(
        id = WorkId.parse("anilist:154587")!!,
        ids = mapOf("anilist" to "154587", "mal" to "52991"),
        title = "Sousou no Frieren",
        format = WorkFormat.TV,
        year = 2023,
    )
    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"
    private val chloe = "@chloe:example.org"

    @BeforeEach
    fun setUp() {
        transport = FakeMatrixRoomTransport()
        bindings = InMemoryRoomBindingStore()
        service = DefaultSocialRoomService(transport, bindings)
    }

    @Test fun `first ensureRoom creates the room with canonical name and topic`() = runBlocking {
        val result = service.ensureRoom(alice, "Alice", alice, work, emptyList())
        assertTrue(result is SocialResult.Success)
        val ensured = (result as SocialResult.Success).value
        assertTrue(ensured.created)
        assertEquals(1, transport.createRoomCalls)
        val name = transport.lastCreateRoomName.orEmpty()
        val topic = transport.lastCreateRoomTopic.orEmpty()
        assertTrue(name.contains("Sousou no Frieren"), "actual name: $name")
        assertTrue(name.contains("Alice"), "actual name: $name")
        assertTrue(topic.contains("anilist:154587"), "actual topic: $topic")
    }

    @Test fun `roomName falls back to workId when display name is null`() = runBlocking {
        service.ensureRoom(alice, null, alice, work, emptyList())
        val name = transport.lastCreateRoomName.orEmpty()
        assertTrue(name.contains("anilist:154587"), "actual name: $name")
    }

    @Test fun `second ensureRoom re-uses the cached binding without recreating`() = runBlocking {
        service.ensureRoom(alice, "Alice", alice, work, emptyList())
        val result = service.ensureRoom(alice, "Alice", alice, work, emptyList())
        assertTrue(result is SocialResult.Success)
        assertFalse((result as SocialResult.Success).value.created)
        assertEquals(1, transport.createRoomCalls)
    }

    @Test fun `friends are passed as invitees and missing-work list is surfaced`() = runBlocking {
        val friends = listOf(
            SocialFriend(bob, hasWork = true),
            SocialFriend(chloe, hasWork = false),
        )
        val result = service.ensureRoom(alice, "Alice", alice, work, friends)
        val ensured = (result as SocialResult.Success).value
        assertEquals(listOf(bob, chloe), transport.lastCreateRoomInvitees)
        assertEquals(listOf(chloe), ensured.plan.missingWork)
    }

    @Test fun `self is never invited`() = runBlocking {
        val friends = listOf(SocialFriend(alice, hasWork = true))
        service.ensureRoom(alice, "Alice", alice, work, friends)
        assertEquals(emptyList<String>(), transport.lastCreateRoomInvitees)
    }

    @Test fun `createRoom failure bubbles up and no binding is written`() = runBlocking {
        transport.failWith(SocialError.Network(transient = true, message = "offline"))
        val result = service.ensureRoom(alice, "Alice", alice, work, emptyList())
        assertTrue(result is SocialResult.Failure)
        val cached = (bindings.resolveRoom(SocialRoomKey(alice, work.id)) as SocialResult.Success).value
        assertEquals(null, cached)
    }

    @Test fun `ensureThreadRoot requires a bound room`() = runBlocking {
        val roomKey = SocialRoomKey(alice, work.id)
        val result = service.ensureThreadRoot(roomKey, "S01E003", work.title)
        assertTrue(result is SocialResult.Failure)
    }

    @Test fun `ensureThreadRoot creates the root then caches it`() = runBlocking {
        service.ensureRoom(alice, "Alice", alice, work, emptyList())
        val roomKey = SocialRoomKey(alice, work.id)
        val first = service.ensureThreadRoot(roomKey, "S01E003", work.title)
        val second = service.ensureThreadRoot(roomKey, "S01E003", work.title)
        assertTrue(first is SocialResult.Success)
        assertTrue(second is SocialResult.Success)
        assertEquals(1, transport.sendStateEventCalls)
        assertEquals((first as SocialResult.Success).value, (second as SocialResult.Success).value)
    }

    @Test fun `initial org shiro room state contains owner and work id`() = runBlocking {
        service.ensureRoom(alice, "Alice", alice, work, emptyList())
        val state = transport.lastCreateRoomState.orEmpty()
        assertTrue(state.contains("\"owner\":\"$alice\""), "actual state: $state")
        assertTrue(state.contains("\"id\":\"anilist:154587\""), "actual state: $state")
        assertTrue(state.contains("\"v\":1") || state.contains("\"v\": 1"), "actual state: $state")
    }

    @Test fun `thread root type is org shiro thread`() = runBlocking {
        service.ensureRoom(alice, "Alice", alice, work, emptyList())
        service.ensureThreadRoot(SocialRoomKey(alice, work.id), "S01E003", work.title)
        assertEquals("org.shiro.thread", transport.lastSendStateType)
    }

    @Test fun `bindRoom failure stops the ensureRoom flow`() = runBlocking {
        val failBindings = object : RoomBindingStore {
            override suspend fun resolveRoom(key: SocialRoomKey) = SocialResult.Success<String?>(null)
            override suspend fun bindRoom(key: SocialRoomKey, roomId: String) =
                SocialResult.Failure(SocialError.Unknown("disk"))
            override suspend fun resolveThreadRoot(key: SocialThreadKey) = SocialResult.Success<String?>(null)
            override suspend fun bindThreadRoot(key: SocialThreadKey, rootEventId: String) = SocialResult.Success(Unit)
        }
        val s = DefaultSocialRoomService(transport, failBindings)
        val result = s.ensureRoom(alice, "Alice", alice, work, emptyList())
        assertTrue(result is SocialResult.Failure)
    }
}