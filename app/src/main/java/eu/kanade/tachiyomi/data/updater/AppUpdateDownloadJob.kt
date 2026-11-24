package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()

    override suspend fun doWork(): Result {
        val url = inputData.getString(EXTRA_DOWNLOAD_URL)
        val title = inputData.getString(EXTRA_DOWNLOAD_TITLE) ?: context.stringResource(MR.strings.app_name)

        if (url.isNullOrEmpty()) {
            return Result.failure()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            logcat(LogPriority.ERROR, e) { "Not allowed to run on foreground service" }
        }

        withIOContext {
            downloadApk(title, url)
        }

        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_APP_UPDATER,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Called to start downloading apk of new update
     *
     * @param url url location of file
     */
    private suspend fun downloadApk(title: String, url: String) {
        // Show notification download starting.
        notifier.onDownloadStarted(title)

        val progressListener = object : ProgressListener {
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last notification sent to avoid posting too many.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                val progress = (100 * (bytesRead.toFloat() / contentLength)).toInt()
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    notifier.onProgressChange(progress)
                }
            }
        }

        try {
            // File where the apk will be saved.
            val apkFile = File(context.externalCacheDir, "update.apk")
            val plan = ShiroUpdatePlanStore(context).forUrl(url)

            // Preferred: only what changed, applied to the installed apk. Any problem falls back
            // to the full download below, so a bad patch never blocks an update.
            val rebuilt = plan?.patchUrl != null && rebuildFromPatch(plan, apkFile, progressListener)
            if (!rebuilt) {
                val response = network.client.newCachelessCallWithProgress(GET(url), progressListener)
                    .await()
                if (response.isSuccessful) {
                    response.body.source().saveTo(apkFile)
                } else {
                    response.close()
                    throw Exception("Unsuccessful response")
                }
                // The file must be the one published, whatever the network did to it.
                if (plan != null && !ApkPatch.sha256Hex(apkFile).equals(plan.apkSha256, ignoreCase = true)) {
                    apkFile.delete()
                    throw Exception("Downloaded apk does not match its checksum")
                }
            }
            notifier.cancel()
            notifier.promptInstall(apkFile.getUriCompat(context))
        } catch (e: Exception) {
            val shouldCancel = e is CancellationException ||
                (e is StreamResetException && e.errorCode == ErrorCode.CANCEL)
            if (shouldCancel) {
                notifier.cancel()
            } else {
                notifier.onDownloadError(url)
            }
        }
    }

    /**
     * Downloads the patch and applies it to the installed apk. Returns false (and leaves no file)
     * when anything goes wrong, so the caller can download the whole apk instead.
     */
    private suspend fun rebuildFromPatch(
        plan: ShiroUpdatePlan,
        target: File,
        progressListener: ProgressListener,
    ): Boolean {
        val patchFile = File(context.externalCacheDir, "update.patch")
        return try {
            val response = network.client.newCachelessCallWithProgress(GET(plan.patchUrl!!), progressListener).await()
            if (!response.isSuccessful) {
                response.close()
                return false
            }
            response.body.source().saveTo(patchFile)

            val installed = File(context.applicationInfo.sourceDir)
            patchFile.inputStream().use { ApkPatch.apply(installed, it, target) }

            val matches = ApkPatch.sha256Hex(target).equals(plan.apkSha256, ignoreCase = true)
            if (!matches) target.delete()
            matches
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Update patch failed, downloading the full apk" }
            target.delete()
            false
        } finally {
            patchFile.delete()
        }
    }

    companion object {
        private const val TAG = "AppUpdateDownload"

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"

        fun start(context: Context, url: String, title: String? = null) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadJob>()
                .setConstraints(constraints)
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        EXTRA_DOWNLOAD_URL to url,
                        EXTRA_DOWNLOAD_TITLE to title,
                    ),
                )
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
