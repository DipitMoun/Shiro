package eu.kanade.tachiyomi.data.telegram.index

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.telegram.saved.TelegramWatchUpdate
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notify

/**
 * Notification de fin de synchronisation Telegram.
 *
 * Le clic ouvre l'onglet Explorer « Telegram ».
 */
class TelegramSyncNotifier(
    private val context: Context,
) {

    fun notify(summaries: List<TelegramSyncSummary>) {
        val updated = summaries.filter { it.newFiles > 0 }
        if (updated.isEmpty()) return

        val totalFiles = updated.sumOf { it.newFiles }
        val title = if (totalFiles == 1) {
            "1 nouveau fichier Telegram"
        } else {
            "$totalFiles nouveaux fichiers Telegram"
        }
        val body = updated.joinToString("\n") { summary ->
            "${summary.channel.title} : +${summary.newFiles} fichiers, " +
                "${summary.newEpisodes} unités"
        }

        context.notify(Notifications.ID_TELEGRAM_SYNC, Notifications.CHANNEL_TELEGRAM_SYNC) {
            setContentTitle(title)
            setContentText(updated.joinToString(", ") { it.channel.title })
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setAutoCancel(true)
            setContentIntent(pendingIntent())
        }
    }

    fun notifyWatched(updates: List<TelegramWatchUpdate>) {
        if (updates.isEmpty()) return

        val total = updates.sumOf { it.added }
        val title = if (total == 1) {
            "1 nouvel épisode Telegram"
        } else {
            "$total nouveaux épisodes Telegram"
        }
        val body = updates.joinToString("\n") { update ->
            "${update.title} : +${update.added}"
        }

        context.notify(Notifications.ID_TELEGRAM_SYNC, Notifications.CHANNEL_TELEGRAM_SYNC) {
            setContentTitle(title)
            setContentText(updates.joinToString(", ") { it.title })
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setAutoCancel(true)
            setContentIntent(pendingIntent())
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Constants.SHORTCUT_TELEGRAM
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
