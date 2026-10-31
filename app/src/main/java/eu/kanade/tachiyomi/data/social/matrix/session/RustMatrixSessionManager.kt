package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production [MatrixSessionManager] (P2-05c-2): orchestrates the session state
 * machine and credential persistence on top of a [SessionDelegate].
 *
 * The Rust SDK adapter ([RustSessionDelegate], P2-05c-3) plugs in as the
 * [SessionDelegate]; unit tests use [FakeSessionDelegate]. The manager itself
 * never imports `org.matrix.rustcomponents.sdk.*`.
 *
 * Behaviour:
 * - [state] transitions Unauthenticated -> Authenticating -> Authenticated / Error
 * - [account] mirrors the current authenticated account, or null
 * - [restore] is safe on a fresh install: returns Success(null), stays Unauthenticated
 * - [logout] is idempotent and always clears persisted credentials
 * - [transportOrNull] returns null unless [state] is [SocialSessionState.Authenticated]
 */
class RustMatrixSessionManager(
    private val delegate: SessionDelegate,
    private val credentialStore: SessionCredentialStore,
) : MatrixSessionManager {

    private val _state = MutableStateFlow<SocialSessionState>(SocialSessionState.Unauthenticated)
    override val state: StateFlow<SocialSessionState> = _state.asStateFlow()

    private val _account = MutableStateFlow<SocialAccount?>(null)
    override val account: StateFlow<SocialAccount?> = _account.asStateFlow()

    override suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String?,
    ): SocialResult<SocialAccount> {
        _state.value = SocialSessionState.Authenticating(homeserverUrl)

        val login = delegate.login(homeserverUrl, userId, password, deviceDisplayName)
        if (login is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(login.error)
            return login
        }
        val session = (login as SocialResult.Success).value

        val save = credentialStore.save(session)
        if (save is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(save.error)
            return save
        }

        val account = session.toAccount()
        _account.value = account
        _state.value = SocialSessionState.Authenticated(account)
        return SocialResult.Success(account)
    }

    override suspend fun restore(): SocialResult<SocialAccount?> {
        _state.value = SocialSessionState.Authenticating(homeserverUrl = null)

        val loaded = credentialStore.load()
        if (loaded is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(loaded.error)
            return loaded
        }
        val persisted = (loaded as SocialResult.Success).value
        if (persisted == null) {
            _state.value = SocialSessionState.Unauthenticated
            return SocialResult.Success(null)
        }

        val restored = delegate.restore(persisted)
        if (restored is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(restored.error)
            return restored
        }
        val session = (restored as SocialResult.Success).value

        val save = credentialStore.save(session)
        if (save is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(save.error)
            return save
        }

        val account = session.toAccount()
        _account.value = account
        _state.value = SocialSessionState.Authenticated(account)
        return SocialResult.Success(account)
    }

    override suspend fun logout(): SocialResult<Unit> {
        delegate.close() // best-effort: even a failing close must not block the clear
        val cleared = credentialStore.clear()
        if (cleared is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(cleared.error)
            return cleared
        }
        _account.value = null
        _state.value = SocialSessionState.Unauthenticated
        return SocialResult.Success(Unit)
    }

    override fun transportOrNull(): MatrixTransport? =
        if (_state.value is SocialSessionState.Authenticated) delegate.transportOrNull() else null

    private fun PersistedSession.toAccount(): SocialAccount = SocialAccount(
        userId = userId,
        deviceId = deviceId,
        homeserverUrl = homeserverUrl,
        displayName = displayName,
    )

    @Suppress("unused")
    private fun authFailure(message: String): SocialResult.Failure = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.UNKNOWN, message),
    )
}