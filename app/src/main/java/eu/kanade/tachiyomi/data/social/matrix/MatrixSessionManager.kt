package eu.kanade.tachiyomi.data.social.matrix

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle owner of the Matrix session backing the social layer (task P2-05).
 *
 * [SocialSession] is the minimal contract that repositories and the command
 * dispatcher depend on: they only need to observe [state] and refuse work with
 * [SocialError.Auth] while the state is not [SocialSessionState.Authenticated].
 *
 * This interface adds the surface consumed by the settings screen and the app
 * bootstrap: login, logout, restore and access to the transport. The Rust SDK
 * adapter (P2-05c) is the production implementation; repositories must never
 * depend on this interface directly.
 */
interface MatrixSessionManager : SocialSession {

    /**
     * Account bound to the current authenticated session, or null while
     * [SocialSessionState.Unauthenticated] or [SocialSessionState.Authenticating].
     */
    val account: StateFlow<SocialAccount?>

    /**
     * Password login against a bring-your-own homeserver.
     *
     * On success, [state] transitions to [SocialSessionState.Authenticated] and
     * credentials are persisted for the next [restore].
     */
    suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String? = null,
    ): SocialResult<SocialAccount>

    /**
     * Reads any persisted credentials and rebuilds the client.
     *
     * Resolves to [SocialResult.Success] with `null` when no credentials are stored
     * (fresh install, explicit logout), so callers can treat "nothing to restore"
     * as a normal case. [SocialResult.Failure] is reserved for a real problem:
     * corrupted store, expired token, unreadable Keystore entry.
     */
    suspend fun restore(): SocialResult<SocialAccount?>

    /**
     * Logs out, drops all credentials and closes the underlying client.
     *
     * Safe to call while unauthenticated: resolves to [SocialResult.Success] in
     * that case (idempotent).
     */
    suspend fun logout(): SocialResult<Unit>

    /**
     * Transport bound to the current session, or null while unauthenticated.
     *
     * Repositories and the command dispatcher must translate a null here into
     * [SocialError.Auth] with [AuthFailureReason.TOKEN_EXPIRED] (or any other
     * appropriate reason) before surfacing an error to the UI.
     */
    fun transportOrNull(): MatrixTransport?
}