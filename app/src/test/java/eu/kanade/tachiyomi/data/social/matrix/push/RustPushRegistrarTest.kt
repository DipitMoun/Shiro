package eu.kanade.tachiyomi.data.social.matrix.push

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RustPushRegistrarTest {

    @Test
    fun `register fails with Auth when no session`() = runBlocking {
        val registrar = RustPushRegistrar(
            clientProvider = { null },
            appId = "io.github.DipitMoun.shiro",
            deviceDisplayName = "test-device",
            lang = "fr",
        )
        val result = registrar.register("https://ntfy.sh/topic123")
        assertTrue(result is SocialResult.Failure)
        val error = (result as SocialResult.Failure).error
        assertTrue(error is SocialError.Auth)
        assertEquals(AuthFailureReason.NOT_ALLOWED, (error as SocialError.Auth).reason)
    }

    @Test
    fun `unregister is idempotent without session`() = runBlocking {
        val registrar = RustPushRegistrar(
            clientProvider = { null },
            appId = "io.app",
            deviceDisplayName = "dev",
            lang = "en",
        )
        val result = registrar.unregister()
        assertTrue(result is SocialResult.Failure)
    }

    @Test
    fun `in-memory registrar tracks state`() = runBlocking {
        val r = InMemoryPushRegistrar()
        assertNull(r.currentEndpoint)
        assertTrue(!r.isRegistered())
        r.register("https://ntfy.sh/foo")
        assertEquals("https://ntfy.sh/foo", r.currentEndpoint)
        assertTrue(r.isRegistered())
        r.unregister()
        assertNull(r.currentEndpoint)
    }

    @Test
    fun `in-memory registrar keeps state on failed register`() = runBlocking {
        val r = InMemoryPushRegistrar()
        r.lastRegisterResult = SocialResult.Failure(SocialError.Network(transient = true))
        r.register("https://ntfy.sh/foo")
        assertNull(r.currentEndpoint)
    }
}
