package eu.kanade.tachiyomi.data.social.friends

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership

/**
 * Matrix has no friend graph. A one-time friend entry is the consent; after
 * that, invitations coming from an accepted friend can be joined automatically.
 */
interface SocialFriendInviteAccepter {
    suspend fun acceptInvitesFromFriends(): SocialResult<Int>

    suspend fun pendingInvites(): SocialResult<List<SocialRoomInvite>> =
        SocialResult.Success(emptyList())

    suspend fun acceptInvite(roomId: String): SocialResult<Unit> = SocialResult.Success(Unit)

    suspend fun declineInvite(roomId: String): SocialResult<Unit> = SocialResult.Success(Unit)

    /** False once the room is joined or left (or unknown to a fake). */
    suspend fun isStillInvited(roomId: String): Boolean = true
}

data class SocialRoomInvite(
    val roomId: String,
    val name: String,
    val inviterMxid: String?,
    val inviterDisplayName: String? = null,
    val inviterAvatarUrl: String? = null,
)

class RustSocialFriendInviteAccepter(
    private val clientProvider: () -> Client?,
    private val friends: SocialFriendStore,
) : SocialFriendInviteAccepter {

    override suspend fun acceptInvitesFromFriends(): SocialResult<Int> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            var joined = 0
            for (room in client.rooms()) {
                if (room.membership() != Membership.INVITED) continue
                val inviter = room.inviter()?.userId ?: continue
                if (!friends.isFriend(inviter)) continue
                room.join()
                joined++
            }
            SocialResult.Success(joined)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(SocialError.Unknown("accept friend invites failed: ${t.message}", t))
        }
    }

    override suspend fun pendingInvites(): SocialResult<List<SocialRoomInvite>> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            SocialResult.Success(
                client.rooms().mapNotNull { room ->
                    if (room.membership() != Membership.INVITED) return@mapNotNull null
                    val inviter = room.inviter()?.userId
                    if (inviter != null && friends.isFriend(inviter)) return@mapNotNull null
                    if (inviter != null && friends.isRejected(inviter)) return@mapNotNull null
                    val displayName = inviter?.let { mxid ->
                        try {
                            room.memberDisplayName(mxid)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    val avatarUrl = inviter?.let { mxid ->
                        try {
                            room.memberAvatarUrl(mxid)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    SocialRoomInvite(
                        roomId = room.id(),
                        name = room.displayName()?.takeIf { it.isNotBlank() } ?: "Room Matrix",
                        inviterMxid = inviter,
                        inviterDisplayName = displayName,
                        inviterAvatarUrl = avatarUrl,
                    )
                },
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(SocialError.Unknown("pending invites failed: ${t.message}", t))
        }
    }

    override suspend fun acceptInvite(roomId: String): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = client.getRoom(roomId)
            // An invitation learnt from a push may not be in the local store yet.
            if (room != null) room.join() else client.joinRoomById(roomId)
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(SocialError.Unknown("accept invite failed: ${t.message}", t))
        }
    }

    override suspend fun declineInvite(roomId: String): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = client.getRoom(roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found"))
            room.leave()
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(SocialError.Unknown("decline invite failed: ${t.message}", t))
        }
    }

    override suspend fun isStillInvited(roomId: String): Boolean {
        val client = clientProvider() ?: return true
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return true
        return runCatching { room.membership() == Membership.INVITED }.getOrDefault(true)
    }

    private fun <T> notAuthenticated(): SocialResult<T> = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )
}
