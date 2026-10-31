package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * In-memory [SessionCredentialStore] used in unit tests and during early bring-up
 * before the EncryptedSharedPreferences store is wired (P2-05c-1b).
 *
 * Thread-safe for the tiny surface used by [RustMatrixSessionManager].
 */
class InMemorySessionCredentialStore : SessionCredentialStore {

    @Volatile
    private var current: PersistedSession? = null

    override suspend fun load(): SocialResult<PersistedSession?> =
        SocialResult.Success(current)

    override suspend fun save(session: PersistedSession): SocialResult<Unit> {
        current = session
        return SocialResult.Success(Unit)
    }

    override suspend fun clear(): SocialResult<Unit> {
        current = null
        return SocialResult.Success(Unit)
    }
}

/**
 * Deterministic failure store used to assert error mapping in tests.
 * Rejects every call with [SocialError.Unknown].
 */
class FailingSessionCredentialStore(
    private val message: String = "credential store unavailable",
) : SessionCredentialStore {
    override suspend fun load(): SocialResult<PersistedSession?> =
        SocialResult.Failure(SocialError.Unknown(message))
    override suspend fun save(session: PersistedSession): SocialResult<Unit> =
        SocialResult.Failure(SocialError.Unknown(message))
    override suspend fun clear(): SocialResult<Unit> =
        SocialResult.Failure(SocialError.Unknown(message))
}