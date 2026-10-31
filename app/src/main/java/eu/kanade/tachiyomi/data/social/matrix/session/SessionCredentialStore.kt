package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * Persists the current authenticated session so [MatrixSessionManager.restore]
 * can rebuild the client at the next boot.
 *
 * Implementations must be idempotent: [save] overwrites, [clear] is safe when no
 * session is stored, and [load] returns [SocialResult.Success] with `null` when
 * nothing is persisted (fresh install, explicit logout).
 *
 * Only [SocialResult.Failure] is reserved for a real problem: Keystore unreadable,
 * corrupted JSON, storage quota exceeded. Never throw for "no session".
 */
interface SessionCredentialStore {
    suspend fun load(): SocialResult<PersistedSession?>
    suspend fun save(session: PersistedSession): SocialResult<Unit>
    suspend fun clear(): SocialResult<Unit>
}