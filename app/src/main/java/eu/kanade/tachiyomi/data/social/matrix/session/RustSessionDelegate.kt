package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
import eu.kanade.tachiyomi.data.social.matrix.RustMatrixClientFactory
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client

/**
 * Production [SessionDelegate] wrapping the Rust Matrix SDK (P2-05c-3a).
 *
 * Owns the lifetime of the SDK [Client]: created on [login] via
 * [RustMatrixClientFactory], reused by [restore], released on [close].
 *
 * The class never throws for expected failures: SDK exceptions are translated to
 * [SocialError.Auth] or [SocialError.Network] so [RustMatrixSessionManager] can
 * drive the state machine uniformly.
 *
 * [transportOrNull] returns null until P2-07 wires the `MatrixTransport` adapter
 * over `Client.sync` / `Client.send` / `Client.redact` / `Client.react`.
 */
class RustSessionDelegate(
    private val factory: RustMatrixClientFactory,
) : SessionDelegate {

    @Volatile
    private var client: Client? = null

    override suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String?,
    ): SocialResult<PersistedSession> {
        val newClient = try {
            factory.create(homeserverUrl)
        } catch (t: Throwable) {
            return SocialResult.Failure(
                SocialError.Unknown("Rust client initialization failed: ${t.message}", t),
            )
        }
        return try {
            newClient.login(
                username = userId,
                password = password,
                initialDeviceName = deviceDisplayName ?: "Shiro",
                deviceId = null,
            )
            client = newClient
            SocialResult.Success(newClient.session().toPersisted(deviceDisplayName))
        } catch (c: CancellationException) {
            newClient.close()
            throw c
        } catch (t: Throwable) {
            newClient.close()
            SocialResult.Failure(mapLoginError(t))
        }
    }

    override suspend fun restore(session: PersistedSession): SocialResult<PersistedSession> {
        val newClient = try {
            factory.create(session.homeserverUrl)
        } catch (t: Throwable) {
            return SocialResult.Failure(
                SocialError.Unknown("Rust client initialization failed: ${t.message}", t),
            )
        }
        return try {
            newClient.restoreSession(session.toSdk())
            client = newClient
            SocialResult.Success(newClient.session().toPersisted(session.displayName))
        } catch (c: CancellationException) {
            newClient.close()
            throw c
        } catch (t: Throwable) {
            newClient.close()
            SocialResult.Failure(mapRestoreError(t))
        }
    }

    override suspend fun close(): SocialResult<Unit> {
        val current = client ?: return SocialResult.Success(Unit)
        client = null
        return try {
            current.close()
            SocialResult.Success(Unit)
        } catch (t: Throwable) {
            SocialResult.Failure(SocialError.Unknown("Rust client close failed: ${t.message}", t))
        }
    }

    override fun transportOrNull(): MatrixTransport? = null

    /**
     * Exposes the live [Client] to sibling transports (room, sync) without
     * leaking it to the rest of the app. Returns null when no session is active.
     *
     * Kept `internal`: only the `data.social.matrix.*` package should see it.
     */
    internal fun clientOrNull(): Client? = client

    private fun mapLoginError(t: Throwable): SocialError {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            "invalid" in lower || "unauthor" in lower || "forbidden" in lower || "m.forbidden" in lower ->
                SocialError.Auth(AuthFailureReason.INVALID_CREDENTIALS, msg)
            "network" in lower || "timed out" in lower || "connect" in lower || "dns" in lower ->
                SocialError.Network(transient = true, message = msg, cause = t)
            "rate" in lower || "too many" in lower ->
                SocialError.RateLimit(retryAfterMs = null)
            else -> SocialError.Unknown("Login failed: $msg", t)
        }
    }

    private fun mapRestoreError(t: Throwable): SocialError {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            "expired" in lower || "invalid token" in lower || "unauthor" in lower ->
                SocialError.Auth(AuthFailureReason.TOKEN_EXPIRED, msg)
            "network" in lower || "timed out" in lower || "connect" in lower || "dns" in lower ->
                SocialError.Network(transient = true, message = msg, cause = t)
            else -> SocialError.Unknown("Session restore failed: $msg", t)
        }
    }
}