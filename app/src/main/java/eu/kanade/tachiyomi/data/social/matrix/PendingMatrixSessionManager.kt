package eu.kanade.tachiyomi.data.social.matrix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skeleton [MatrixSessionManager] used until the Rust SDK adapter lands in P2-05c.
 *
 * The class does not import the Matrix SDK. It records state transitions so
 * [SocialSession] consumers (repositories, command dispatcher, UI gating via
 * [SocialFeatureFlags]) can be developed and unit-tested in isolation.
 *
 * Behaviour:
 * - [state] stays [SocialSessionState.Unauthenticated]; [account] stays null;
 * - [transportOrNull] returns null;
 * - [restore] returns [SocialResult.Success] with null, matching a fresh install;
 * - [login] and [logout] return [SocialError.Auth] with
 *   [AuthFailureReason.NOT_ALLOWED] until the real adapter replaces them.
 */
class PendingMatrixSessionManager : MatrixSessionManager {

    private val _state = MutableStateFlow<SocialSessionState>(SocialSessionState.Unauthenticated)
    override val state: StateFlow<SocialSessionState> = _state.asStateFlow()

    private val _account = MutableStateFlow<SocialAccount?>(null)
    override val account: StateFlow<SocialAccount?> = _account.asStateFlow()

    override suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String?,
    ): SocialResult<SocialAccount> = notImplemented("login")

    override suspend fun restore(): SocialResult<SocialAccount?> =
        SocialResult.Success(null)

    override suspend fun logout(): SocialResult<Unit> = notImplemented("logout")

    override fun transportOrNull(): MatrixTransport? = null

    private fun <T> notImplemented(op: String): SocialResult<T> = SocialResult.Failure(
        SocialError.Auth(
            reason = AuthFailureReason.NOT_ALLOWED,
            message = "MatrixSessionManager.$op is not wired yet (P2-05c)",
        ),
    )
}