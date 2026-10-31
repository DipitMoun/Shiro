package eu.kanade.tachiyomi.data.social.matrix

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatrixSessionManagerTest {

    private val manager = PendingMatrixSessionManager()

    @Test
    fun `initial state is Unauthenticated and account is null`() {
        assertEquals(SocialSessionState.Unauthenticated, manager.state.value)
        assertNull(manager.account.value)
    }

    @Test
    fun `transportOrNull is null while unauthenticated`() {
        assertNull(manager.transportOrNull())
    }

    @Test
    fun `restore returns Success(null) when nothing is persisted`() {
        val result = runBlocking { manager.restore() }
        assertTrue(result is SocialResult.Success<SocialAccount?>)
        assertNull((result as SocialResult.Success<SocialAccount?>).value)
    }

    @Test
    fun `login fails with Auth NOT_ALLOWED until Rust SDK is wired`() {
        val result = runBlocking {
            manager.login(
                homeserverUrl = "https://matrix.org",
                userId = "@alice:matrix.org",
                password = "secret",
            )
        }
        assertTrue(result is SocialResult.Failure)
        val error = (result as SocialResult.Failure).error
        assertTrue(error is SocialError.Auth)
        assertEquals(AuthFailureReason.NOT_ALLOWED, (error as SocialError.Auth).reason)
    }

    @Test
    fun `logout fails with Auth NOT_ALLOWED until Rust SDK is wired`() {
        val result = runBlocking { manager.logout() }
        assertTrue(result is SocialResult.Failure)
        val error = (result as SocialResult.Failure).error
        assertTrue(error is SocialError.Auth)
        assertEquals(AuthFailureReason.NOT_ALLOWED, (error as SocialError.Auth).reason)
    }
}