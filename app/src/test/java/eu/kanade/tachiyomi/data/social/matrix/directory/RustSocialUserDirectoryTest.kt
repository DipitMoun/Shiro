package eu.kanade.tachiyomi.data.social.matrix.directory

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RustSocialUserDirectoryTest {

    @Test
    fun `search fails with Auth when no session`() = runBlocking {
        val directory = RustSocialUserDirectory(clientProvider = { null })

        val result = directory.search("alice")

        assertTrue(result is SocialResult.Failure)
        val error = (result as SocialResult.Failure).error
        assertTrue(error is SocialError.Auth)
        assertEquals(AuthFailureReason.NOT_ALLOWED, (error as SocialError.Auth).reason)
    }

    @Test
    fun `blank query returns an empty page without a session`() = runBlocking {
        val directory = RustSocialUserDirectory(clientProvider = { null })

        val result = directory.search("   ")

        assertTrue(result is SocialResult.Success)
        assertTrue((result as SocialResult.Success).value.users.isEmpty())
    }
}
