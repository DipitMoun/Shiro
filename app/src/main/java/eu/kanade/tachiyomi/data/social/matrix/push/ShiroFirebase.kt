package eu.kanade.tachiyomi.data.social.matrix.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.resume

/**
 * Firebase, initialized by hand from the values Gradle reads in google-services.json.
 *
 * Without that file (CI, forks) or for a variant it does not list, push is simply disabled
 * and the app keeps working with the foreground sync service only.
 */
object ShiroFirebase {

    @Volatile
    private var initialized = false

    /** @return true when Firebase is ready for this app id. */
    fun init(context: Context): Boolean {
        if (initialized) return true
        val appId = appIdFor(context.packageName) ?: return false
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(appId)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            initialized = true
            true
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Firebase init failed, social push disabled" }
        }.getOrDefault(false)
    }

    val isAvailable: Boolean get() = initialized

    /** Current FCM registration token, or null when push is unavailable. */
    suspend fun token(): String? {
        if (!initialized) return null
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener {
                    logcat(LogPriority.WARN, it) { "FCM token unavailable" }
                    continuation.resume(null)
                }
        }
    }

    private fun appIdFor(packageName: String): String? =
        BuildConfig.FIREBASE_APP_IDS
            .split(';')
            .mapNotNull { entry ->
                val (pkg, id) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                pkg to id
            }
            .firstOrNull { (pkg, _) -> pkg == packageName }
            ?.second
            ?.takeIf { it.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() }
}
