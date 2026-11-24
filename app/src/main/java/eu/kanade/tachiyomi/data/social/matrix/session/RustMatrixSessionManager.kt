package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.session.RustSessionDelegate
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
    private val accountDataCleaner: SocialAccountDataCleaner = NoOpSocialAccountDataCleaner,
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

    override suspend fun startOAuth(homeserverUrl: String, create: Boolean): SocialResult<String> {
        _state.value = SocialSessionState.Authenticating(homeserverUrl)
        val started = delegate.startOAuth(homeserverUrl, create)
        // The user now leaves for the browser: nothing is signed in until [completeOAuth].
        _state.value = when (started) {
            is SocialResult.Failure -> SocialSessionState.Error(started.error)
            is SocialResult.Success -> SocialSessionState.Unauthenticated
        }
        return started
    }

    override suspend fun completeOAuth(callbackUrl: String): SocialResult<SocialAccount> {
        _state.value = SocialSessionState.Authenticating(homeserverUrl = null)
        val finished = delegate.finishOAuth(callbackUrl)
        if (finished is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(finished.error)
            return finished
        }
        val session = (finished as SocialResult.Success).value

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
        val closed = delegate.close() // best-effort: never block clearing local data
        if (closed is SocialResult.Failure) {
            android.util.Log.e("SocialOverlay", "logout: client close failed", null)
        }
        val wiped = delegate.wipeLocalData()
        if (wiped is SocialResult.Failure) {
            android.util.Log.e("SocialOverlay", "logout: Matrix store wipe failed", null)
        }
        accountDataCleaner.clear()

        val cleared = credentialStore.clear()
        if (cleared is SocialResult.Failure) {
            _state.value = SocialSessionState.Error(cleared.error)
            return cleared
        }
        _account.value = null
        _state.value = SocialSessionState.Unauthenticated
        return SocialResult.Success(Unit)
    }


    override fun clientOrNull(): org.matrix.rustcomponents.sdk.Client? = (delegate as? RustSessionDelegate)?.clientOrNull()

    override fun roomListServiceOrNull(): org.matrix.rustcomponents.sdk.RoomListService? =
        (delegate as? RustSessionDelegate)?.roomListServiceOrNull()

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