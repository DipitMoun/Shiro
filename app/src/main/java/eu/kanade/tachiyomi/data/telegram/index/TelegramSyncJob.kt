package eu.kanade.tachiyomi.data.telegram.index

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkManager
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthManager
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Synchronisation périodique des canaux Telegram suivis.
 *
 * Seuls les index locaux sont mis à jour : aucun fichier n'est téléchargé.
 * Les nouveaux fichiers déclenchent une notification qui ouvre l'onglet
 * Explorer « Telegram ».
 */
class TelegramSyncJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val preferences: TelegramPreferences = Injekt.get()
        val savedStore: TelegramSavedWorkStore = Injekt.get()
        val hasWatch = savedStore.snapshot().any { it.watchEnabled }

        // La veille par œuvre fonctionne même si la synchro globale est coupée.
        if (!preferences.autoSyncEnabled && !hasWatch) return Result.success()

        val authManager: TelegramAuthManager = Injekt.get()
        if (!awaitReady(authManager)) return Result.success()

        val manager: TelegramIndexManager = Injekt.get()
        if (preferences.autoSyncEnabled) {
            val summaries = runCatching { manager.syncAll() }
                .onFailure { logcat(LogPriority.WARN, it) { "Synchronisation Telegram impossible" } }
                .getOrElse { return Result.success() }

            if (preferences.notificationsEnabled) {
                TelegramSyncNotifier(applicationContext).notify(summaries)
            }
        }

        val savedWorkManager: TelegramSavedWorkManager = Injekt.get()
        val updates = runCatching {
            savedWorkManager.runDue(syncChannels = !preferences.autoSyncEnabled)
        }
            .onFailure { logcat(LogPriority.WARN, it) { "Veille des œuvres Telegram impossible" } }
            .getOrDefault(emptyList())

        if (preferences.notificationsEnabled && updates.isNotEmpty()) {
            TelegramSyncNotifier(applicationContext).notifyWatched(updates)
        }

        return Result.success()
    }

    /**
     * TDLib doit être démarré pour restaurer la session. On attend un état
     * terminal avant de synchroniser, sans bloquer indéfiniment le worker.
     */
    private suspend fun awaitReady(authManager: TelegramAuthManager): Boolean {
        authManager.start()

        return withTimeoutOrNull(READY_TIMEOUT_MS) {
            authManager.authState.first { state ->
                state is TelegramAuthState.Ready ||
                    state is TelegramAuthState.Error ||
                    state is TelegramAuthState.Closed ||
                    state is TelegramAuthState.WaitingPhoneNumber ||
                    state is TelegramAuthState.WaitingRegistration
            }
            authManager.authState.value is TelegramAuthState.Ready
        } ?: false
    }

    companion object {
        private const val TAG = "TelegramSync"
        private const val WORK_NAME = "TelegramSync-periodic"
        private const val READY_TIMEOUT_MS = 60_000L
        private const val MIN_INTERVAL_MINUTES = 15

        fun setupTask(context: Context) {
            val preferences: TelegramPreferences = Injekt.get()
            val savedWorks: TelegramSavedWorkStore = Injekt.get()
            if (!preferences.autoSyncEnabled && savedWorks.snapshot().none { it.watchEnabled }) {
                context.workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val minWatch = savedWorks.snapshot()
                .filter { it.watchEnabled }
                .minOfOrNull { it.watchIntervalMinutes }

            val intervalMinutes = when {
                preferences.autoSyncEnabled && minWatch != null -> {
                    minOf(preferences.syncIntervalMinutes, minWatch)
                }
                preferences.autoSyncEnabled -> preferences.syncIntervalMinutes
                minWatch != null -> minWatch
                else -> MIN_INTERVAL_MINUTES
            }.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<TelegramSyncJob>(
                intervalMinutes,
                TimeUnit.MINUTES,
                5,
                TimeUnit.MINUTES,
            )
                .addTag(TAG)
                .setConstraints(constraints)
                .build()

            context.workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
