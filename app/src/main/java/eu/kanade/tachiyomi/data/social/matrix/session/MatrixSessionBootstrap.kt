package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Restores the persisted Matrix session once, whoever asks first.
 *
 * The app, the sync service started at boot and notification actions can all run in a
 * cold process at the same time; [MatrixSessionManager.restore] must not run twice.
 */
object MatrixSessionBootstrap {

    private val mutex = Mutex()

    /** @return true when a Matrix session is authenticated after the call. */
    suspend fun ensureRestored(session: MatrixSessionManager): Boolean = mutex.withLock {
        when (session.state.value) {
            is SocialSessionState.Authenticated -> true
            // A login is in progress in the settings: never interfere with it.
            is SocialSessionState.Authenticating -> false
            is SocialSessionState.Unauthenticated,
            is SocialSessionState.Error,
            -> {
                session.restore()
                session.state.value is SocialSessionState.Authenticated
            }
        }
    }
}
