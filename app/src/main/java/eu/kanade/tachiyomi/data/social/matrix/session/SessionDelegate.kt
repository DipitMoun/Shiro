package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
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
     * Restores a `Client` from [session] and validates it. On success, resolves
     * to the session snapshot after any refresh (tokens may have rotated).
     */
    suspend fun restore(session: PersistedSession): SocialResult<PersistedSession>

    /** Shuts the underlying `Client` down and frees native resources. */
    suspend fun close(): SocialResult<Unit>

    /**
     * Transport bound to the live session, or null when no client is open.
     * [RustMatrixSessionManager] only exposes it while [SocialSessionState.Authenticated].
     */
    fun transportOrNull(): MatrixTransport?
}