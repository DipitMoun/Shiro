package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionCredentialStoreTest {

    private val sample = PersistedSession(
        userId = "@alice:matrix.org",
        deviceId = "DEVICE1",
        homeserverUrl = "https://matrix.org",
        displayName = "Alice",
        accessToken = "syt_secret",
        refreshToken = "refresh_secret",
        oauthData = null,
        slidingSyncVersion = "NONE",
    )

    @Test fun `load on fresh store returns Success(null)`() = runBlocking {
        val store = InMemorySessionCredentialStore()
        val result = store.load()
        assertTrue(result is SocialResult.Success<PersistedSession?>)
        assertNull((result as SocialResult.Success<PersistedSession?>).value)
    }

    @Test fun `save then load returns the same session`() = runBlocking {
        val store = InMemorySessionCredentialStore()
        store.save(sample)
        val loaded = (store.load() as SocialResult.Success<PersistedSession?>).value
        assertEquals(sample, loaded)
    }

    @Test fun `save twice overwrites the previous session`() = runBlocking {
        val store = InMemorySessionCredentialStore()
        store.save(sample)
        val updated = sample.copy(displayName = "Alice 2")
        store.save(updated)
        val loaded = (store.load() as SocialResult.Success<PersistedSession?>).value
        assertEquals("Alice 2", loaded?.displayName)
    }

    @Test fun `clear removes the persisted session`() = runBlocking {
        val store = InMemorySessionCredentialStore()
        store.save(sample)
        store.clear()
        assertNull((store.load() as SocialResult.Success<PersistedSession?>).value)
    }

    @Test fun `clear on empty store is idempotent`() = runBlocking {
        val store = InMemorySessionCredentialStore()
        store.clear()
        store.clear()
        assertNull((store.load() as SocialResult.Success<PersistedSession?>).value)
    }

    @Test fun `account view projects the persisted fields`() {
        val view = sample.account
        assertEquals(sample.userId, view.userId)
        assertEquals(sample.deviceId, view.deviceId)
        assertEquals(sample.homeserverUrl, view.homeserverUrl)
        assertEquals(sample.displayName, view.displayName)
    }

    @Test fun `failing store surfaces SocialError Unknown on load`() = runBlocking {
        val result = FailingSessionCredentialStore("disk failure").load()
        assertTrue(result is SocialResult.Failure)
        val err = (result as SocialResult.Failure).error
        assertTrue(err is SocialError.Unknown)
        assertEquals("disk failure", (err as SocialError.Unknown).message)
    }

    @Test fun `failing store surfaces SocialError Unknown on save`() = runBlocking {
        val result = FailingSessionCredentialStore().save(sample)
        assertTrue(result is SocialResult.Failure)
        assertTrue((result as SocialResult.Failure).error is SocialError.Unknown)
    }
}