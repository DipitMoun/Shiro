package eu.kanade.tachiyomi.data.social.matrix.push

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Keeps a Matrix HTTP pusher registered for this device's FCM token.
 *
 * The homeserver then calls the Shiro push gateway for every notifiable event, which relays
 * it to Firebase and wakes the phone immediately, even in Doze.
 */
class SocialPushManager(
    context: Context,
    private val session: MatrixSessionManager,
    private val registrar: PushRegistrar,
) {

    private val prefs = context.getSharedPreferences("social_push", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /**
     * Registers the pusher when the token, the account or the gateway changed. Cheap to call
     * often: an unchanged registration is skipped.
     */
    suspend fun ensureRegistered(newToken: String? = null) = mutex.withLock {
        if (!ShiroFirebase.isAvailable) return@withLock
        val account = (session.state.value as? SocialSessionState.Authenticated)?.account ?: return@withLock
        val token = newToken ?: ShiroFirebase.token() ?: return@withLock
        val gateway = BuildConfig.SOCIAL_PUSH_GATEWAY

        val signature = "${account.userId}|$token|$gateway"
        if (prefs.getString(KEY_SIGNATURE, null) == signature) return@withLock

        when (val result = registrar.register(pushkey = token, gatewayUrl = gateway)) {
            is SocialResult.Success -> {
                prefs.edit { putString(KEY_SIGNATURE, signature) }
                logcat(LogPriority.INFO) { "social pusher registered" }
            }
            is SocialResult.Failure -> logcat(LogPriority.WARN) {
                "social pusher registration failed: ${result.error}"
            }
        }
    }

    /**
     * True when this device receives Matrix pushes for the current account. The permanent
     * sync service is then useless: it only runs as a fallback when push is unavailable
     * (no Google services, build without google-services.json, registration failed).
     */
    fun isPushReady(): Boolean {
        if (!ShiroFirebase.isAvailable) return false
        val account = (session.state.value as? SocialSessionState.Authenticated)?.account ?: return false
        return prefs.getString(KEY_SIGNATURE, null)?.startsWith("${account.userId}|") == true
    }

    /** Forget the registration so the next session registers again (logout, account switch). */
    fun reset() {
        prefs.edit { remove(KEY_SIGNATURE) }
    }

    private companion object {
        const val KEY_SIGNATURE = "registered_signature"
    }
}
