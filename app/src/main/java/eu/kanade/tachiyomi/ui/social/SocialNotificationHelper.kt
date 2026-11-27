package eu.kanade.tachiyomi.ui.social

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.social.activity.SocialActivityItem
import eu.kanade.tachiyomi.data.social.activity.SocialActivityKind
import eu.kanade.tachiyomi.data.social.friends.SocialRoomInvite
import eu.kanade.tachiyomi.ui.main.MainActivity
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Builds and posts social notifications. Does not create channels: see
 * [Notifications.createChannels] for the channel declarations.
 */
object SocialNotificationHelper {

    const val KEY_TEXT_REPLY = "key_text_reply"
    const val EXTRA_ROOM_ID = "roomId"

    private const val MAX_MESSAGES = 8

    private fun messageTag(roomId: String) = "social:$roomId"
    private fun inviteTag(requestKey: String) = "social_invite:$requestKey"
    private fun shortcutId(roomId: String) = "social_$roomId"

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun chatIntent(
        context: Context,
        roomId: String,
        workId: String?,
        authorMxid: String?,
        roomTitle: String?,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = "social.room.$roomId"
        putExtra(EXTRA_ROOM_ID, roomId)
        putExtra("workId", workId)
        putExtra("authorMxid", authorMxid)
        putExtra("roomTitle", roomTitle)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    private fun broadcast(
        context: Context,
        receiver: Class<*>,
        action: String,
        roomId: String,
        requestCode: Int,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, receiver).apply {
            this.action = "$action.$roomId"
            putExtra(EXTRA_ROOM_ID, roomId)
        }
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    /** Persistent notification shown by [eu.kanade.tachiyomi.data.social.matrix.sync.MatrixSyncService]. */
    fun buildSyncNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, Notifications.CHANNEL_SOCIAL_SYNC)
            .setSmallIcon(R.drawable.ic_ani)
            .setContentTitle(context.stringResource(MR.strings.social_notification_sync_title))
            .setContentText(context.stringResource(MR.strings.social_notification_sync_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    /**
     * Conversation notification for a new message in a friend room.
     *
     * Messages of the same room stack in one notification (up to [MAX_MESSAGES]) and the
     * room is published as a long-lived conversation shortcut, so Android lists it in the
     * Conversations section with the sender avatar.
     */
    fun showMessage(
        context: Context,
        roomId: String,
        workId: String?,
        authorMxid: String?,
        selfMxid: String,
        senderDisplayName: String,
        senderMxid: String,
        body: String,
        timestamp: Long,
        avatarBytes: ByteArray?,
        roomTitle: String?,
    ) {
        val senderIcon = avatarBytes
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?.let(IconCompat::createWithBitmap)
        val sender = Person.Builder()
            .setName(senderDisplayName)
            .setKey(senderMxid)
            .apply { senderIcon?.let(::setIcon) }
            .build()
        val self = Person.Builder()
            .setName(context.stringResource(MR.strings.social_notification_self))
            .setKey(selfMxid)
            .build()

        val title = roomTitle?.takeIf { it.isNotBlank() } ?: senderDisplayName
        val style = activeMessagingStyle(context, roomId) ?: NotificationCompat.MessagingStyle(self)
        style.addMessage(NotificationCompat.MessagingStyle.Message(body, timestamp, sender))
        while (style.messages.size > MAX_MESSAGES) style.messages.removeAt(0)
        style.conversationTitle = title
        style.isGroupConversation = true

        val intent = chatIntent(context, roomId, workId, authorMxid, roomTitle)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(roomId))
            .setShortLabel(title)
            .setLongLived(true)
            .setIntent(intent)
            .setPerson(sender)
            .setLocusId(LocusIdCompat(shortcutId(roomId)))
            .setIcon(senderIcon ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }

        val contentIntent = PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_reply_24dp,
            context.stringResource(MR.strings.social_notification_reply),
            broadcast(context, SocialReplyReceiver::class.java, "social.reply", roomId, roomId.hashCode() xor 0x5A5A, mutable = true),
        )
            .addRemoteInput(
                RemoteInput.Builder(KEY_TEXT_REPLY)
                    .setLabel(context.stringResource(MR.strings.social_notification_reply))
                    .build(),
            )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_done_24dp,
            context.stringResource(MR.strings.social_notification_mark_read),
            broadcast(context, SocialMarkReadReceiver::class.java, "social.read", roomId, roomId.hashCode() xor 0x2B2B),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SOCIAL_MESSAGES)
            .setSmallIcon(R.drawable.ic_ani)
            .setStyle(style)
            .setShortcutId(shortcutId(roomId))
            .setLocusId(LocusIdCompat(shortcutId(roomId)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        post(context, messageTag(roomId), Notifications.ID_SOCIAL_MESSAGES, notification)
    }

    fun cancelMessages(context: Context, roomId: String) {
        NotificationManagerCompat.from(context).cancel(messageTag(roomId), Notifications.ID_SOCIAL_MESSAGES)
    }

    /** Friend request with Accept / Decline straight from the notification. */
    /**
     * @param requestKey identifies the person: one notification per friend request, whatever
     * the number of rooms they invited us to.
     */
    fun showInvite(context: Context, invite: SocialRoomInvite, requestKey: String, avatarBytes: ByteArray?) {
        val name = invite.inviterDisplayName
            ?: invite.inviterMxid?.substringBefore(':')?.removePrefix("@")
            ?: context.stringResource(MR.strings.social_notification_invite_unknown)
        val text = context.stringResource(MR.strings.social_notification_invite_text, name)
        val details = invite.inviterMxid?.let {
            context.stringResource(MR.strings.social_notification_invite_details, name, it)
        } ?: text
        val avatar = avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val code = invite.roomId.hashCode()

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SOCIAL_INVITES)
            .setSmallIcon(R.drawable.ic_ani)
            .setLargeIcon(avatar)
            .setContentTitle(context.stringResource(MR.strings.social_notification_invite_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                R.drawable.ic_done_24dp,
                context.stringResource(MR.strings.social_notification_accept),
                broadcast(context, SocialInviteActionReceiver::class.java, SocialInviteActionReceiver.ACTION_ACCEPT, invite.roomId, code xor 0x1111),
            )
            .addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.social_notification_decline),
                broadcast(context, SocialInviteActionReceiver::class.java, SocialInviteActionReceiver.ACTION_DECLINE, invite.roomId, code xor 0x2222),
            )
            .build()
        post(context, inviteTag(requestKey), Notifications.ID_SOCIAL_INVITES, notification)
    }

    /** "Moun added Frieren, you might like it": only for works the user does not have. */
    fun showActivity(
        context: Context,
        activity: SocialActivityItem,
        actorName: String,
        avatarBytes: ByteArray?,
        banner: Bitmap? = null,
    ) {
        val avatar = avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val verb = when (activity.kind) {
            SocialActivityKind.ANIME_ADDED -> "a ajouté"
            SocialActivityKind.MANGA_ADDED -> "lit maintenant"
        }
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SOCIAL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_ani)
            .setLargeIcon(avatar)
            .setContentTitle("$actorName $verb ${activity.title}")
            .setContentText("Tu ne l'as pas encore, ça pourrait te plaire")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setWhen(activity.timestamp)
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context))
            .apply {
                // Expanded, the cover takes the place of the avatar and fills the notification.
                if (banner != null) {
                    setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(banner)
                            .bigLargeIcon(null as Bitmap?),
                    )
                }
            }
            .build()
        post(context, "social_activity:${activity.eventId}", Notifications.ID_SOCIAL_ACTIVITY, notification)
    }

    /** A friend posted a story. One notification per friend: a new story replaces the previous one. */
    fun showStory(context: Context, ownerMxid: String, ownerName: String, timestamp: Long, avatarBytes: ByteArray?) {
        val avatar = avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SOCIAL_STORIES)
            .setSmallIcon(R.drawable.ic_ani)
            .setLargeIcon(avatar)
            .setContentTitle(ownerName)
            .setContentText("a publié une story")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context))
            .build()
        post(context, "social_story:$ownerMxid", Notifications.ID_SOCIAL_STORIES, notification)
    }

    fun cancelInvite(context: Context, requestKey: String) {
        NotificationManagerCompat.from(context).cancel(inviteTag(requestKey), Notifications.ID_SOCIAL_INVITES)
    }

    /** Messages already shown for this room, so a new one is appended instead of replacing them. */
    private fun activeMessagingStyle(context: Context, roomId: String): NotificationCompat.MessagingStyle? {
        val manager = context.getSystemService<NotificationManager>() ?: return null
        val active = runCatching { manager.activeNotifications }.getOrNull() ?: return null
        val notification = active.firstOrNull {
            it.tag == messageTag(roomId) && it.id == Notifications.ID_SOCIAL_MESSAGES
        }?.notification ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    }

    private fun post(context: Context, tag: String?, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked: drop silently.
        }
    }
}
