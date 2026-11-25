package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.powerManager

/**
 * Keeps a download going with the screen off or another app in front: a foreground service alone
 * does not stop the CPU or the Wi-Fi radio from sleeping, which is what slows or stops a download.
 * Held only while a download job runs.
 */
internal class DownloadKeepAlive(context: Context, tag: String) {

    private val appContext = context.applicationContext

    private val wakeLock = appContext.powerManager
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shiro:$tag")
        .apply { setReferenceCounted(false) }

    private val wifiLock = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            },
            "Shiro:$tag",
        )
        ?.apply { setReferenceCounted(false) }

    fun acquire() {
        runCatching { wakeLock.acquire(MAX_HOLD_MS) }
        runCatching { wifiLock?.acquire() }
        suggestBatteryExemption()
    }

    fun release() {
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    /**
     * Phone makers kill background apps on their own, whatever the app does. Say it once, with a
     * one-tap way to allow Shiro, the first time a download runs without that permission.
     */
    private fun suggestBatteryExemption() {
        if (appContext.powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) return
        val prefs = appContext.getSharedPreferences(HINT_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(HINT_KEY, false)) return
        prefs.edit().putBoolean(HINT_KEY, true).apply()

        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            0,
            request,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        appContext.notify(
            Notifications.ID_DOWNLOAD_BACKGROUND_HINT,
            Notifications.CHANNEL_DOWNLOADER_ERROR,
        ) {
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setContentTitle("Téléchargements en arrière-plan")
            setContentText("Autorise Shiro à rester actif pour que l'écran éteint ne ralentisse plus les téléchargements.")
            setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    "Autorise Shiro à rester actif pour que l'écran éteint ou une autre application ne ralentisse plus les téléchargements.",
                ),
            )
            setContentIntent(pending)
            addAction(0, "Autoriser", pending)
            setAutoCancel(true)
        }
    }

    private companion object {
        const val MAX_HOLD_MS = 4 * 60 * 60 * 1000L
        const val HINT_PREFS = "shiro_download_hint"
        const val HINT_KEY = "battery_hint_shown"
    }
}
