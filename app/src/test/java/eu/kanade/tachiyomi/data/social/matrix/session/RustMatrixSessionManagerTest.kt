package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RustMatrixSessionManagerTest {

    private lateinit var delegate: FakeSessionDelegate
    private lateinit var store: InMemorySessionCredentialStore
    private lateinit var manager: RustMatrixSessionManager

    @BeforeEach
    fun setUp() {
        delegate = FakeSessionDelegate()
        store = InMemorySessionCredentialStore()
        manager = RustMatrixSessionManager(delegate, store)
    }

    @Test fun `initial state is Unauthenticated and account null`() {
        assertEquals(SocialSessionState.Unauthenticated, manager.state.value)
        assertNull(manager.account.value)
    }

    @Test fun `successful login persists and transitions to Authenticated`() = runBlocking {
        val result = manager.login("https://matrix.org", "@alice:matrix.org", "secret", "Pixel")
        assertTrue(result is SocialResult.Success)
        assertTrue(manager.state.value is SocialSessionState.Authenticated)
        assertEquals("@alice:matrix.org", manager.account.value?.userId)
        assertEquals(1, delegate.loginCalls)
        val persisted = (store.load() as SocialResult.Success<PersistedSession?>).value
        assertEquals("@alice:matrix.org", persisted?.userId)
    }

    @Test fun `failed login maps Auth error to Error state`() = runBlocking {
        delegate.loginResult = SocialResult.Failure(
            SocialError.Auth(AuthFailureReason.INVALID_CREDENTIALS, "bad creds"),
        )
        val result = manager.login("https://matrix.org", "@alice:matrix.org", "wrong", null)
        assertTrue(result is SocialResult.Failure)
        val err = (manager.state.value as SocialSessionState.Error).error
        assertTrue(err is SocialError.Auth)
        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, (err as SocialError.Auth).reason)
        assertNull(manager.account.value)
        assertNull((store.load() as SocialResult.Success<PersistedSession?>).value)
    }

    @Test fun `failed login network maps to Error Network`() = runBlocking {
        delegate.loginResult = SocialResult.Failure(SocialError.Network(transient = true, message = "DNS"))
        manager.login("https://matrix.org", "@alice:matrix.org", "x", null)
        val err = (manager.state.value as SocialSessionState.Error).error
        assertTrue(err is SocialError.Network)
    }

    @Test fun `restore on fresh install returns Success(null) and stays Unauthenticated`() = runBlocking {
        val result = manager.restore()
        assertTrue(result is SocialResult.Success<*>)
        assertNull((result as SocialResult.Success<*>).value)
        assertEquals(SocialSessionState.Unauthenticated, manager.state.value)
        assertEquals(0, delegate.restoreCalls)
    }

    @Test fun `restore with persisted session transitions to Authenticated`() = runBlocking {
        store.save(FakeSessionDelegate.SAMPLE)
        val result = manager.restore()
        assertTrue(result is SocialResult.Success)
        assertTrue(manager.state.value is SocialSessionState.Authenticated)
        assertEquals("@alice:matrix.org", manager.account.value?.userId)
        assertEquals(1, delegate.restoreCalls)
    }

    @Test fun `restore failure from delegate goes to Error`() = runBlocking {
        store.save(FakeSessionDelegate.SAMPLE)
        delegate.restoreResult = SocialResult.Failure(
            SocialError.Auth(AuthFailureReason.TOKEN_EXPIRED, "expired"),
        )
        val result = manager.restore()
        assertTrue(result is SocialResult.Failure)
        val err = (manager.state.value as SocialSessionState.Error).error
        assertTrue(err is SocialError.Auth)
        assertEquals(AuthFailureReason.TOKEN_EXPIRED, (err as SocialError.Auth).reason)
    }

    @Test fun `logout clears credentials and returns Unauthenticated`() = runBlocking {
        store.save(FakeSessionDelegate.SAMPLE)
        manager.restore()
        val result = manager.logout()
        assertTrue(result is SocialResult.Success)
        assertEquals(SocialSessionState.Unauthenticated, manager.state.value)
        assertNull(manager.account.value)
        assertNull((store.load() as SocialResult.Success<PersistedSession?>).value)
        assertEquals(1, delegate.closeCalls)
    }

    @Test fun `logout on fresh state is idempotent`() = runBlocking {
        val result = manager.logout()
        assertTrue(result is SocialResult.Success)
        assertEquals(SocialSessionState.Unauthenticated, manager.state.value)
    }

    @Test fun `transportOrNull is null when Unauthenticated`() {
        assertNull(manager.transportOrNull())
    }

    @Test fun `transportOrNull delegates when Authenticated`() = runBlocking {
        val stub = object : eu.kanade.tachiyomi.data.social.matrix.MatrixTransport {
            override suspend fun sync(request: eu.kanade.tachiyomi.data.social.matrix.SyncRequest) =
                SocialResult.Failure(SocialError.Unknown("stub"))
            override suspend fun join(roomIdOrAlias: String) =
                SocialResult.Failure(SocialError.Unknown("stub"))
            override suspend fun send(request: eu.kanade.tachiyomi.data.social.matrix.SendRequest) =
                SocialResult.Failure(SocialError.Unknown("stub"))
            override suspend fun redact(roomId: String, eventId: String, reason: String?) =
                SocialResult.Failure(SocialError.Unknown("stub"))
            override suspend fun react(roomId: String, eventId: String, key: String) =
                SocialResult.Failure(SocialError.Unknown("stub"))
        }
        delegate.transport = stub
        manager.login("https://matrix.org", "@alice:matrix.org", "secret", null)
        assertNotNull(manager.transportOrNull())
    }
}