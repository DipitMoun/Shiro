package eu.kanade.tachiyomi.data.social.matrix.profile

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client

/** Own-profile writes kept separate from the read-only media loader. */
interface SocialProfileEditor {
    /** Uploads [data] and applies it as the current account avatar. */
    suspend fun updateAvatar(mimeType: String, data: ByteArray): SocialResult<Unit>
}

class RustSocialProfileEditor(
    private val clientProvider: () -> Client?,
) : SocialProfileEditor {

    override suspend fun updateAvatar(mimeType: String, data: ByteArray): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()
        if (data.isEmpty()) {
            return SocialResult.Failure(SocialError.Unknown("Avatar image is empty"))
        }
        return try {
            withContext(Dispatchers.IO) {
                client.uploadAvatar(mimeType, data)
            }
            SocialResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t))
        }
    }

    private fun mapError(t: Throwable): SocialError {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            "unauthor" in lower || "invalid token" in lower || "expired" in lower ->
                SocialError.Auth(AuthFailureReason.TOKEN_EXPIRED, msg)
            "forbidden" in lower || "m.forbidden" in lower ->
                SocialError.Permission(msg)
            "network" in lower || "connect" in lower || "timed out" in lower || "dns" in lower ->
                SocialError.Network(transient = true, message = msg, cause = t)
            "rate" in lower || "too many" in lower ->
                SocialError.RateLimit(retryAfterMs = null)
            else -> SocialError.Unknown("Avatar update failed: $msg", t)
        }
    }

    private fun <T> notAuthenticated(): SocialResult<T> = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )
}
