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
}

data class SocialRoomInvite(
    val roomId: String,
    val name: String,
    val inviterMxid: String?,
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
                    SocialRoomInvite(
                        roomId = room.id(),
                        name = room.displayName()?.takeIf { it.isNotBlank() } ?: "Room Matrix",
                        inviterMxid = inviter,
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
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found"))
            room.join()
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

    private fun <T> notAuthenticated(): SocialResult<T> = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )
}
