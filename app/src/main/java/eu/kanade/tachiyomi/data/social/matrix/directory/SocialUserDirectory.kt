package eu.kanade.tachiyomi.data.social.matrix.directory

import eu.kanade.tachiyomi.data.social.friends.SocialProfileLink
import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.UserProfile

data class SocialDirectoryUser(
    val mxid: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

data class SocialUserSearchPage(
    val users: List<SocialDirectoryUser>,
    /** True when the homeserver truncated the result set. */
    val limited: Boolean = false,
)

/** Reads the homeserver user directory without exposing SDK types to the UI. */
interface SocialUserDirectory {
    suspend fun search(query: String, limit: Int = 20): SocialResult<SocialUserSearchPage>
}

class RustSocialUserDirectory(
    private val clientProvider: () -> Client?,
) : SocialUserDirectory {

    override suspend fun search(query: String, limit: Int): SocialResult<SocialUserSearchPage> {
        val term = query.trim()
        if (term.isEmpty()) {
            return SocialResult.Success(SocialUserSearchPage(emptyList()))
        }
        val client = clientProvider() ?: return notAuthenticated()

        // A full MXID works even when the homeserver's directory search is
        // disabled or only indexes display names.
        if (SocialProfileLink.isValidMxid(term)) {
            val exact = resolveExactProfile(client, term)?.toDirectoryUser(fallbackMxid = term)
            if (exact != null) {
                return SocialResult.Success(
                    SocialUserSearchPage(
                        users = listOf(exact),
                        limited = false,
                    ),
                )
            }
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                client.searchUsers(term, limit.coerceIn(1, 50).toULong())
            }
            val users = result.results
                .mapNotNull { it.toDirectoryUser() }
                .filter { SocialProfileLink.isValidMxid(it.mxid) }
                .distinctBy { it.mxid }
            SocialResult.Success(
                SocialUserSearchPage(
                    users = users,
                    limited = result.limited,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "User search"))
        }
    }

    private suspend fun resolveExactProfile(client: Client, mxid: String): UserProfile? = try {
        withContext(Dispatchers.IO) {
            client.getProfile(mxid)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        // Fall through to the directory search: not every homeserver allows
        // fetching an arbitrary profile, but it may still index the MXID.
        null
    }

    private fun UserProfile.toDirectoryUser(fallbackMxid: String? = null): SocialDirectoryUser? {
        val resolvedMxid = userId.takeIf { it.isNotBlank() } ?: fallbackMxid ?: return null
        if (!SocialProfileLink.isValidMxid(resolvedMxid)) return null
        return SocialDirectoryUser(
            mxid = resolvedMxid,
            displayName = displayName?.takeIf { it.isNotBlank() },
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() && it.startsWith("mxc://") },
        )
    }

    private fun mapError(t: Throwable, op: String): SocialError {
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
            else -> SocialError.Unknown("$op failed: $msg", t)
        }
    }

    private fun <T> notAuthenticated(): SocialResult<T> = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )
}
