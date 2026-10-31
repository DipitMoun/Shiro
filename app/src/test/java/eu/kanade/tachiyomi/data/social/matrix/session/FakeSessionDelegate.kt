package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * Scriptable [SessionDelegate] for [RustMatrixSessionManagerTest].
 *
 * Each operation can be configured to return a fixed [SocialResult] and tracks
 * how many times it was called so the manager's orchestration can be asserted.
 */
class FakeSessionDelegate : SessionDelegate {

    var loginResult: SocialResult<PersistedSession> = SocialResult.Success(SAMPLE)
    var restoreResult: SocialResult<PersistedSession> = SocialResult.Success(SAMPLE)
    var closeResult: SocialResult<Unit> = SocialResult.Success(Unit)
    var transport: MatrixTransport? = null

    var loginCalls: Int = 0; private set
    var restoreCalls: Int = 0; private set
    var closeCalls: Int = 0; private set

    override suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String?,
    ): SocialResult<PersistedSession> { loginCalls++; return loginResult }

    override suspend fun restore(session: PersistedSession): SocialResult<PersistedSession> {
        restoreCalls++; return restoreResult
    }

    override suspend fun close(): SocialResult<Unit> { closeCalls++; return closeResult }

    override fun transportOrNull(): MatrixTransport? = transport

    companion object {
        val SAMPLE = PersistedSession(
            userId = "@alice:matrix.org",
            deviceId = "DEVICE1",
            homeserverUrl = "https://matrix.org",
            displayName = "Alice",
            accessToken = "syt_test",
            refreshToken = null,
            oauthData = null,
            slidingSyncVersion = "NONE",
        )
    }
}