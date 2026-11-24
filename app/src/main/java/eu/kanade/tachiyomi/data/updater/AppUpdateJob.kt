package eu.kanade.tachiyomi.data.updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import java.util.concurrent.TimeUnit

/**
 * Looks for a new Shiro version in the background and says so with a notification, so an update
 * is noticed without opening the app. The notification starts the download.
 */
class AppUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!updaterEnabled) return Result.success()

        return try {
            val result = AppUpdateChecker().checkForUpdate(context)
            if (result is GetApplicationRelease.Result.NewUpdate) {
                val release = result.release
                AppUpdateNotifier(context).promptUpdate(
                    release.copy(releaseLink = release.releaseLink.ifBlank { RELEASE_URL }),
                )
            }
            Result.success()
        } catch (e: Exception) {
            // No network, or the manifest is not published yet: try again at the next period.
            logcat(LogPriority.WARN, e) { "Update check failed" }
            Result.success()
        }
    }

    companion object {
        private const val TAG = "AppUpdateCheck"
        private const val INTERVAL_HOURS = 12L

        fun setupTask(context: Context) {
            if (!updaterEnabled) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AppUpdateJob>(INTERVAL_HOURS, TimeUnit.HOURS)
                .addTag(TAG)
                .setConstraints(constraints)
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
