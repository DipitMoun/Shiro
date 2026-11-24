package eu.kanade.tachiyomi.data.social.matrix.profile

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RustSocialProfileEditorTest {

    @Test
    fun `updateAvatar fails with Auth when no session`() = runBlocking {
        val editor = RustSocialProfileEditor(clientProvider = { null })

        val result = editor.updateAvatar("image/jpeg", byteArrayOf(1, 2, 3))

        assertTrue(result is SocialResult.Failure)
        val error = (result as SocialResult.Failure).error
        assertTrue(error is SocialError.Auth)
        assertEquals(AuthFailureReason.NOT_ALLOWED, (error as SocialError.Auth).reason)
    }
}
