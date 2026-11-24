package eu.kanade.tachiyomi.data.social.matrix.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.social.matrix.push.SocialPushManager
import eu.kanade.tachiyomi.ui.social.SocialNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Keeps the app process alive so [SocialLiveListener] receives messages and friend requests
 * in the background.
 *
 * Always on while a Matrix account is connected. Push alone is not enough on many phones:
 * OEM freezers (Transsion Hiber, MIUI, ...) freeze an app woken by a push within seconds,
 * and only a running foreground service is spared. Push still wakes the service if it was
 * killed, and makes delivery instant out of Doze.
 *
 * Runs as a `remoteMessaging` foreground service: no daily time limit, and it may be
 * started from a boot receiver or a high-priority push on Android 15+. Its notification
 * uses a minimal, silent channel.
 */
class MatrixSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener: SocialLiveListener by lazy { Injekt.get() }
    private val pushManager: SocialPushManager by lazy { Injekt.get() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Started at boot or by a push, the app UI never ran: channels may not exist yet.
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SOCIAL_SYNC,
            SocialNotificationHelper.buildSyncNotification(this),
            foregroundType(),
        )
        listener.start()
        scope.launch {
            runCatching { pushManager.ensureRegistered() }
                .onFailure { logcat(LogPriority.WARN, it) { "push registration failed" } }
        }
        return START_STICKY
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }

    override fun onDestroy() {
        // The listener keeps running in-process while the app is open.
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MatrixSyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MatrixSyncService::class.java))
        }
    }
}
