package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * Translates [PersistedSession] operations into real Matrix SDK calls (P2-05c-3)
 * or into deterministic fakes (tests).
 *
 * Kept free of SDK types so [RustMatrixSessionManager] can be unit-tested without
 * the Rust component. Production adapter ([RustSessionDelegate]) is the only file
 * allowed to import `org.matrix.rustcomponents.sdk.*` in this layer.
 */
interface SessionDelegate {

    /**
     * Password login. On success, resolves to the freshly issued session that
     * must be persisted; the delegate keeps the live `Client` in memory.
     */
    suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String?,
    ): SocialResult<PersistedSession>

    /**
     * Starts a browser-based (OAuth/OIDC) sign-in or sign-up and resolves to the URL to open.
     * [create] opens the account creation page instead of the login page. The attempt is
     * finished by [finishOAuth] once the browser redirects back into the app.
     */
    suspend fun startOAuth(homeserverUrl: String, create: Boolean): SocialResult<String> =
        SocialResult.Failure(SocialError.Unknown("OAuth sign-in is not supported"))

    /** Completes the attempt begun by [startOAuth] from the redirect [callbackUrl]. */
    suspend fun finishOAuth(callbackUrl: String): SocialResult<PersistedSession> =
        SocialResult.Failure(SocialError.Unknown("OAuth sign-in is not supported"))

    /**
     * Restores a `Client` from [session] and validates it. On success, resolves
     * to the session snapshot after any refresh (tokens may have rotated).
     */
    suspend fun restore(session: PersistedSession): SocialResult<PersistedSession>

    /** Shuts the underlying `Client` down and frees native resources. */
    suspend fun close(): SocialResult<Unit>

    /**
     * Deletes the account-scoped local SDK store after [close]. Used on logout so
     * a different Matrix account never reopens the previous account's crypto store.
     */
    suspend fun wipeLocalData(): SocialResult<Unit> = SocialResult.Success(Unit)

    /**
     * Transport bound to the live session, or null when no client is open.
     * [RustMatrixSessionManager] only exposes it while [SocialSessionState.Authenticated].
     */
    fun transportOrNull(): MatrixTransport?
}