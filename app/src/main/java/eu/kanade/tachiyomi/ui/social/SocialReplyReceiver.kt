package eu.kanade.tachiyomi.ui.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.room.MatrixRoomTransport
import eu.kanade.tachiyomi.data.social.matrix.sync.SocialNotificationCursor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import org.matrix.rustcomponents.sdk.ReceiptType
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Handles inline replies from social message notifications.
 *
 * The notification exposes a [RemoteInput]; this receiver forwards the text to the room
 * transport, marks the room as read and clears the notification.
 */
class SocialReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(SocialNotificationHelper.EXTRA_ROOM_ID) ?: return
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(SocialNotificationHelper.KEY_TEXT_REPLY)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return

        runWithSession("inline reply") { session ->
            val content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", replyText)
            }
            when (val result = Injekt.get<MatrixRoomTransport>().sendMessage(roomId, content.toString())) {
                is SocialResult.Success -> {
                    Injekt.get<SocialNotificationCursor>().markRead(roomId)
                    SocialNotificationHelper.cancelMessages(context, roomId)
                    runCatching { session.clientOrNull()?.getRoom(roomId)?.markAsRead(ReceiptType.READ) }
                }
                is SocialResult.Failure -> logcat(LogPriority.ERROR) {
                    "Inline reply failed for room $roomId: ${result.error}"
                }
            }
        }
    }
}
