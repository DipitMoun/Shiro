package eu.kanade.tachiyomi.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Once a week, checks the extension repos and says so when one stops answering, moves to a
 * format the app cannot read, or starts offering extensions the app cannot load. Silent while
 * nothing changes, so it never nags about a problem already reported.
 */
class ExtensionRepoHealthJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val report = ExtensionRepoHealthChecker().checkAll()
            val signature = RepoHealthRules.signature(report)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getString(KEY_SIGNATURE, "").orEmpty()
            prefs.edit().putString(KEY_SIGNATURE, signature).apply()

            val problems = report.filter { it.hasProblem }
            if (signature.isNotEmpty() && signature != previous) notify(problems)
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Repo health check failed" }
            Result.success()
        }
    }

    private fun notify(problems: List<RepoHealth>) {
        val names = problems.joinToString(", ") { it.name }
        val text = if (problems.size == 1) {
            "Le dépôt « $names » demande ton attention."
        } else {
            "${problems.size} dépôts demandent ton attention : $names."
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.notify(Notifications.ID_EXTENSION_REPO_HEALTH, Notifications.CHANNEL_EXTENSIONS_UPDATE) {
            setSmallIcon(R.drawable.ic_extension_24dp)
            setContentTitle("Dépôts d'extensions")
            setContentText(text)
            setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$text Ouvre Réglages → Navigation → Santé des dépôts."))
            if (open != null) {
                setContentIntent(
                    PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            setAutoCancel(true)
        }
    }

    companion object {
        private const val TAG = "ExtensionRepoHealth"
        private const val PREFS = "shiro_repo_health"
        private const val KEY_SIGNATURE = "signature"

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExtensionRepoHealthJob>(7, TimeUnit.DAYS)
                .addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
