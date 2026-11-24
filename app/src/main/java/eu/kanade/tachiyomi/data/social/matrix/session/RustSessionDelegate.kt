package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MatrixTransport
import eu.kanade.tachiyomi.data.social.matrix.RustMatrixClientFactory
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.OAuthConfiguration
import org.matrix.rustcomponents.sdk.OAuthPrompt
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk.OAuthAuthorizationData

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
    private val credentialStore: SessionCredentialStore? = null,
) : SessionDelegate {

    @Volatile
    private var client: Client? = null

    /** Client and authorization of a browser sign-in waiting for its redirect. */
    private var pendingOAuthClient: Client? = null
    private var pendingOAuthData: OAuthAuthorizationData? = null

    /**
     * OAuth refresh tokens rotate: the SDK hands every renewed session back here so the
     * persisted copy never goes stale.
     */
    private val tokenPersistence = object : ClientSessionDelegate {
        override fun retrieveSessionFromKeychain(userId: String): Session = runBlocking {
            val stored = (credentialStore?.load() as? SocialResult.Success)?.value
            requireNotNull(stored) { "no persisted session" }.toSdk()
        }

        override fun saveSessionInKeychain(session: Session) {
            val store = credentialStore ?: return
            runBlocking {
                val name = (store.load() as? SocialResult.Success)?.value?.displayName
                store.save(session.toPersisted(name))
            }
        }
    }

    override suspend fun startOAuth(homeserverUrl: String, create: Boolean): SocialResult<String> {
        close()
        discardPendingOAuth()
        if (!factory.wipeLocalStore()) {
            android.util.Log.e("SocialOverlay", "oauth: failed to wipe previous Matrix store")
        }
        val newClient = try {
            factory.create(homeserverUrl, tokenPersistence)
        } catch (t: Throwable) {
            return SocialResult.Failure(
                SocialError.Unknown("Rust client initialization failed: ${t.message}", t),
            )
        }
        return try {
            if (!newClient.homeserverLoginDetails().supportsOauthLogin()) {
                newClient.close()
                return SocialResult.Failure(
                    SocialError.Unknown("Ce serveur ne propose pas la connexion par navigateur"),
                )
            }
            val data = newClient.urlForOauth(
                oauthConfiguration = OAuthConfiguration(
                    clientName = OAUTH_CLIENT_NAME,
                    redirectUri = OAUTH_REDIRECT_URI,
                    clientUri = OAUTH_CLIENT_URI,
                    logoUri = null,
                    tosUri = null,
                    policyUri = null,
                    staticRegistrations = emptyMap(),
                ),
                prompt = if (create) OAuthPrompt.Create else OAuthPrompt.Login,
                loginHint = null,
                deviceId = null,
                additionalScopes = null,
            )
            pendingOAuthClient = newClient
            pendingOAuthData = data
            SocialResult.Success(data.loginUrl())
        } catch (c: CancellationException) {
            newClient.close()
            throw c
        } catch (t: Throwable) {
            newClient.close()
            SocialResult.Failure(SocialError.Unknown("OAuth start failed: ${t.message}", t))
        }
    }

    override suspend fun finishOAuth(callbackUrl: String): SocialResult<PersistedSession> {
        val newClient = pendingOAuthClient
            ?: return SocialResult.Failure(
                SocialError.Unknown("Aucune inscription en cours : relance la création du compte"),
            )
        return try {
            newClient.loginWithOauthCallback(callbackUrl)
            pendingOAuthClient = null
            pendingOAuthData?.close()
            pendingOAuthData = null
            client = newClient
            startSync(newClient)
            SocialResult.Success(newClient.session().toPersisted())
        } catch (c: CancellationException) {
            discardPendingOAuth()
            throw c
        } catch (t: Throwable) {
            discardPendingOAuth()
            SocialResult.Failure(SocialError.Unknown("OAuth sign-in failed: ${t.message}", t))
        }
    }

    private suspend fun discardPendingOAuth() {
        val pendingClient = pendingOAuthClient
        val pendingData = pendingOAuthData
        pendingOAuthClient = null
        pendingOAuthData = null
        runCatching {
            if (pendingClient != null && pendingData != null) pendingClient.abortOauthAuth(pendingData)
        }
        runCatching { pendingData?.close() }
        runCatching { pendingClient?.close() }
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var syncService: SyncService? = null
    private var syncJob: Job? = null
    private var syncStateHandle: TaskHandle? = null

    override suspend fun login(
        homeserverUrl: String,
        userId: String,
        password: String,
        deviceDisplayName: String?,
    ): SocialResult<PersistedSession> {
        close()
        // A password login always starts a new device. Never reuse the previous
        // account's encrypted SQLite/crypto store, even if a logout wipe failed.
        if (!factory.wipeLocalStore()) {
            android.util.Log.e("SocialOverlay", "login: failed to wipe previous Matrix store")
        }

        val newClient = try {
            factory.create(homeserverUrl, tokenPersistence)
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
            startSync(newClient)
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
        // Reuse the running client: rebuilding it on every activity recreation
        // creates a second SyncService and detaches the notification listeners.
        val activeClient = client
        if (activeClient != null && syncService != null) {
            val activeUserId = try {
                activeClient.userId()
            } catch (_: Throwable) {
                null
            }
            if (activeUserId == session.userId) {
                return SocialResult.Success(session)
            }
        }

        val newClient = try {
            factory.create(session.homeserverUrl, tokenPersistence)
        } catch (t: Throwable) {
            return SocialResult.Failure(
                SocialError.Unknown("Rust client initialization failed: ${t.message}", t),
            )
        }
        return try {
            newClient.restoreSession(session.toSdk())
            client = newClient
            startSync(newClient)
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
        val current = client
        client = null
        val stopResult = stopSync()
        if (current == null) return stopResult
        return try {
            current.close()
            stopResult
        } catch (t: Throwable) {
            SocialResult.Failure(SocialError.Unknown("Rust client close failed: ${t.message}", t))
        }
    }

    override suspend fun wipeLocalData(): SocialResult<Unit> =
        if (factory.wipeLocalStore()) {
            SocialResult.Success(Unit)
        } else {
            SocialResult.Failure(
                SocialError.Unknown("Failed to delete the local Matrix store"),
            )
        }

    private fun startSync(client: Client) {
        syncJob = syncScope.launch {
            try {
                android.util.Log.e("SocialOverlay", "startSync: building SyncService")
                val service = client.syncService().finish()
                syncService = service
                android.util.Log.e("SocialOverlay", "startSync: calling start()")
                service.start()
                android.util.Log.e("SocialOverlay", "startSync: SUCCESS (state loop starting)")

                superviseSync(service)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.e("SocialOverlay", "startSync FAILED", t)
            }
        }
    }

    /**
     * Restarts the sync after an error.
     *
     * A network drop (Doze, OEM battery savers, switching networks) puts the SyncService in
     * ERROR and it never restarts by itself: without this, nothing new reached the app until
     * it was relaunched.
     */
    private fun superviseSync(service: SyncService) {
        syncStateHandle?.cancel()
        var restartDelayMs = SYNC_RESTART_MIN_MS
        syncStateHandle = service.state(object : org.matrix.rustcomponents.sdk.SyncServiceStateObserver {
            override fun onUpdate(state: SyncServiceState) {
                android.util.Log.i("SocialSync", "Sync state: $state")
                when (state) {
                    SyncServiceState.RUNNING -> restartDelayMs = SYNC_RESTART_MIN_MS
                    SyncServiceState.ERROR -> {
                        val delayMs = restartDelayMs
                        restartDelayMs = (restartDelayMs * 2).coerceAtMost(SYNC_RESTART_MAX_MS)
                        syncScope.launch {
                            delay(delayMs)
                            // Logged out or replaced meanwhile: leave it alone.
                            if (syncService !== service) return@launch
                            android.util.Log.i("SocialSync", "restarting sync after error")
                            runCatching { service.start() }
                                .onFailure { android.util.Log.w("SocialSync", "sync restart failed", it) }
                        }
                    }
                    else -> Unit
                }
            }
        })
    }

    private suspend fun stopSync(): SocialResult<Unit> {
        val job = syncJob
        syncJob = null
        job?.cancelAndJoin()
        syncStateHandle?.cancel()
        syncStateHandle = null

        val svc = syncService ?: return SocialResult.Success(Unit)
        syncService = null
        return try {
            svc.stop()
            android.util.Log.e("SocialOverlay", "stopSync: stopped")
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.e("SocialOverlay", "stopSync FAILED", t)
            SocialResult.Failure(SocialError.Unknown("Sync stop failed: ${t.message}", t))
        }
    }

    override fun transportOrNull(): MatrixTransport? = null

    /**
     * Exposes the live [Client] to sibling transports (room, sync) without
     * leaking it to the rest of the app. Returns null when no session is active.
     *
     * Kept `internal`: only the `data.social.matrix.*` package should see it.
     */
    fun clientOrNull(): Client? = client

    /**
     * Room list owned by the already-running [SyncService]. The caller must not
     * close it or build a second sync service.
     */
    fun roomListServiceOrNull(): RoomListService? = syncService?.roomListService()

    /** The running sync service, needed to build a notification client. */
    fun syncServiceOrNull(): SyncService? = syncService

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

    private companion object {
        const val SYNC_RESTART_MIN_MS = 2_000L
        const val SYNC_RESTART_MAX_MS = 60_000L

        /** Registered with matrix.org's account service; the scheme mirrors [OAUTH_CLIENT_URI]. */
        const val OAUTH_CLIENT_NAME = "Shiro"
        const val OAUTH_CLIENT_URI = "https://dipitmoun.workers.dev"
        const val OAUTH_REDIRECT_URI = "dev.workers.dipitmoun:/oauth"
    }
}
