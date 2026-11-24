package eu.kanade.tachiyomi.ui.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.social.friends.SocialInviteMonitor
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.session.MatrixSessionBootstrap
import eu.kanade.tachiyomi.data.social.matrix.session.PersistentSessionCredentialStore
import eu.kanade.tachiyomi.data.social.matrix.sync.MatrixSyncService
import eu.kanade.tachiyomi.data.social.matrix.sync.SocialNotificationCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.matrix.rustcomponents.sdk.ReceiptType
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Runs [block] off the main thread with an authenticated Matrix session; notification
 * actions can arrive in a cold process.
 */
internal fun BroadcastReceiver.runWithSession(tag: String, block: suspend (MatrixSessionManager) -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            val session = Injekt.get<MatrixSessionManager>()
            if (MatrixSessionBootstrap.ensureRestored(session)) {
                block(session)
            } else {
                logcat(LogPriority.WARN) { "$tag: no Matrix session" }
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "$tag failed" }
        } finally {
            pending.finish()
        }
    }
}

/** "Marquer comme lu" on a message notification. */
class SocialMarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(SocialNotificationHelper.EXTRA_ROOM_ID) ?: return
        SocialNotificationHelper.cancelMessages(context, roomId)
        Injekt.get<SocialNotificationCursor>().markRead(roomId)
        runWithSession("mark read") { session ->
            session.clientOrNull()?.getRoom(roomId)?.markAsRead(ReceiptType.READ)
        }
    }
}

/** Accept / Decline on a friend request notification. */
class SocialInviteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(SocialNotificationHelper.EXTRA_ROOM_ID) ?: return
        val accept = intent.action?.startsWith(ACTION_ACCEPT) == true
        runWithSession("invite action") {
            val monitor = Injekt.get<SocialInviteMonitor>()
            if (accept) monitor.accept(roomId) else monitor.decline(roomId)
        }
    }

    companion object {
        const val ACTION_ACCEPT = "social.invite.accept"
        const val ACTION_DECLINE = "social.invite.decline"
    }
}

/** Restarts the Matrix sync after a reboot or an app update, without opening the app. */
class SocialBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        if (!PersistentSessionCredentialStore.hasPersistedSession(context)) return
        runCatching { MatrixSyncService.start(context) }
            .onFailure { logcat(LogPriority.WARN, it) { "social sync start at boot failed" } }
    }
}
