package eu.kanade.tachiyomi.data.social.friends

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import eu.kanade.tachiyomi.ui.social.SocialNotificationHelper
import eu.kanade.tachiyomi.ui.social.SocialNotificationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Single source of truth for pending friend requests.
 *
 * Fed live by the sync service (room list updates), it drives both the in-app popup and
 * the system notification, so a request shows up without restarting the app.
 */
class SocialInviteMonitor(
    private val context: Context,
    private val accepter: SocialFriendInviteAccepter,
    private val friends: SocialFriendStore,
    private val roomService: SocialRoomService,
    private val session: MatrixSessionManager,
    private val avatarLoader: SocialAvatarLoader,
) {

    private val _invites = MutableStateFlow<List<SocialRoomInvite>>(emptyList())
    val invites: StateFlow<List<SocialRoomInvite>> = _invites.asStateFlow()

    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("social_invites", Context.MODE_PRIVATE)

    /** Every pending room invitation, several per person (one per work room). */
    @Volatile
    private var pendingRooms: List<SocialRoomInvite> = emptyList()

    /**
     * Invitations learnt from a push. The main sync may be stale (or restarting) when the
     * push arrives, so they are kept until accepted, declined, or seen by the sync.
     */
    @Volatile
    private var pushedRooms: List<SocialRoomInvite> = emptyList()

    /**
     * Re-reads invitations: joins rooms of existing friends, exposes and notifies the others.
     *
     * A friend invites us to each of their work rooms: requests are grouped by inviter so a
     * person shows up once, with a stable representative room.
     */
    suspend fun refresh() = mutex.withLock {
        accepter.acceptInvitesFromFriends()
        val fetched = (accepter.pendingInvites() as? SocialResult.Success)?.value ?: return@withLock
        val fetchedIds = fetched.mapTo(mutableSetOf()) { it.roomId }
        pushedRooms = pushedRooms.filter { it.roomId !in fetchedIds && accepter.isStillInvited(it.roomId) }
        publish(fetched + pushedRooms)
    }

    /**
     * A push carried an invitation: show the request right away, without waiting for the
     * main sync. Invitations from existing friends are joined directly.
     */
    suspend fun onInvitePushed(invite: SocialRoomInvite) = mutex.withLock {
        val inviter = invite.inviterMxid
        if (inviter != null && friends.isFriend(inviter)) {
            logcat(LogPriority.INFO) { "invites: $inviter is a friend, joining ${invite.roomId}" }
            accepter.acceptInvite(invite.roomId)
            return@withLock
        }
        if (inviter != null && friends.isRejected(inviter)) {
            logcat(LogPriority.INFO) { "invites: $inviter was rejected, ignoring" }
            return@withLock
        }
        if (pendingRooms.none { it.roomId == invite.roomId }) {
            pushedRooms = pushedRooms.filterNot { it.roomId == invite.roomId } + invite
        }
        publish((pendingRooms + pushedRooms).distinctBy { it.roomId })
    }

    private suspend fun publish(pending: List<SocialRoomInvite>) {
        pendingRooms = pending
        val requests = pending
            .sortedBy { it.roomId }
            .distinctBy { it.requestKey() }
        _invites.value = requests

        val requestKeys = requests.mapTo(mutableSetOf()) { it.requestKey() }
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()

        // Requests handled elsewhere (another device, Element) leave the shade too. The
        // notification is keyed by person, not by room: the representative room of a person
        // changes as their invitations arrive one by one.
        val shown = prefs.getStringSet(KEY_SHOWN_REQUESTS, emptySet()).orEmpty()
        (shown - requestKeys).forEach { SocialNotificationHelper.cancelInvite(context, it) }

        val fresh = requests.filter { it.requestKey() !in notified }
        val foreground = SocialNotificationState.isAppForeground
        logcat(LogPriority.INFO) {
            "invites: ${requests.size} request(s), ${fresh.size} new, foreground=$foreground"
        }
        if (!foreground) {
            fresh.forEach { invite ->
                val avatar = invite.inviterMxid?.let { mxid ->
                    runCatching { avatarLoader.loadProfileAvatar(mxid, AVATAR_SIZE) }.getOrNull()
                }
                SocialNotificationHelper.showInvite(context, invite, invite.requestKey(), avatar)
            }
        }
        // In the foreground the popup shows the request: it counts as seen.
        prefs.edit {
            putStringSet(KEY_NOTIFIED, (notified intersect requestKeys) + fresh.map { it.requestKey() })
            putStringSet(KEY_SHOWN_REQUESTS, requestKeys)
        }
    }

    /** Accepts a friend request: joins every room the person invited us to. */
    suspend fun accept(roomId: String): SocialResult<Unit> {
        val invite = find(roomId)
        val rooms = roomsOf(invite, roomId)
        val result = accepter.acceptInvite(roomId)
        if (result is SocialResult.Success) {
            rooms.filter { it != roomId }.forEach { other ->
                accepter.acceptInvite(other)
            }
            val inviter = invite?.inviterMxid
            if (inviter != null) {
                friends.add(SocialFriendRecord(mxid = inviter, displayName = invite.inviterDisplayName))
                val self = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId
                if (self != null) {
                    runCatching { roomService.inviteFriendToOwnRooms(inviter, self) }
                        .onFailure { logcat(LogPriority.WARN, it) { "invite back failed for $inviter" } }
                }
            }
            forget(invite, roomId, rooms)
        }
        return result
    }

    /** Declines a friend request: leaves every room the person invited us to. */
    suspend fun decline(roomId: String): SocialResult<Unit> {
        val invite = find(roomId)
        val rooms = roomsOf(invite, roomId)
        val result = accepter.declineInvite(roomId)
        if (result is SocialResult.Success) {
            rooms.filter { it != roomId }.forEach { other -> accepter.declineInvite(other) }
            invite?.inviterMxid?.let { friends.rejectFriendRequest(it) }
            forget(invite, roomId, rooms)
        }
        return result
    }

    /** Notification actions may run in a cold process where the lists are still empty. */
    private suspend fun find(roomId: String): SocialRoomInvite? {
        (pendingRooms + pushedRooms).firstOrNull { it.roomId == roomId }?.let { return it }
        val pending = (accepter.pendingInvites() as? SocialResult.Success)?.value.orEmpty()
        pendingRooms = pending
        return pending.firstOrNull { it.roomId == roomId }
    }

    private fun roomsOf(invite: SocialRoomInvite?, roomId: String): List<String> {
        val inviter = invite?.inviterMxid ?: return listOf(roomId)
        return ((pendingRooms + pushedRooms).filter { it.inviterMxid == inviter }.map { it.roomId } + roomId)
            .distinct()
    }

    private fun forget(invite: SocialRoomInvite?, roomId: String, rooms: List<String>) {
        val key = invite?.requestKey() ?: roomId
        pendingRooms = pendingRooms.filterNot { it.roomId in rooms }
        pushedRooms = pushedRooms.filterNot { it.roomId in rooms }
        _invites.value = _invites.value.filterNot { it.requestKey() == key || it.roomId in rooms }
        SocialNotificationHelper.cancelInvite(context, key)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        prefs.edit { putStringSet(KEY_NOTIFIED, notified - key) }
    }

    /** One request per person; invitations without a known inviter stay separate. */
    private fun SocialRoomInvite.requestKey(): String = inviterMxid ?: "room:$roomId"

    private companion object {
        // v2: keyed by inviter instead of room.
        const val KEY_NOTIFIED = "notified_requests_v2"
        const val KEY_SHOWN_REQUESTS = "shown_requests"
        const val AVATAR_SIZE = 128
    }
}
