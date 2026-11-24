package eu.kanade.tachiyomi.data.social.friends

import eu.kanade.tachiyomi.data.social.activity.SocialActivityCodec
import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import org.matrix.rustcomponents.sdk.Membership
import tachiyomi.core.common.util.system.logcat

/**
 * Ends a friendship on both sides of Matrix, not only in the local list.
 *
 * A friend is someone always invited to our work rooms; removing them means:
 * - leaving the rooms they own (and declining their pending invitations);
 * - removing them from the rooms we own.
 *
 * After that, adding each other again goes through a fresh friend request.
 */
class SocialFriendRemover(
    private val session: MatrixSessionManager,
    private val friends: SocialFriendStore,
) {

    /** @return the number of rooms left or cleaned up. */
    suspend fun unfriend(friendMxid: String): SocialResult<Int> {
        val client = session.clientOrNull() ?: return SocialResult.Failure(
            SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
        )
        val selfMxid = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId

        var changed = 0
        try {
            for (room in client.rooms()) {
                val topic = room.topic().orEmpty()
                val ownerMxid = SocialRoomKey.parseTopic(topic)?.authorMxid
                    ?: SocialActivityCodec.ownerOf(topic)
                val removed = runCatching {
                    when (room.membership()) {
                        Membership.INVITED -> {
                            if (room.inviter()?.userId == friendMxid) {
                                room.leave()
                                true
                            } else {
                                false
                            }
                        }
                        Membership.JOINED -> when (ownerMxid) {
                            friendMxid -> {
                                room.leave()
                                true
                            }
                            // Kicking also revokes a pending invitation.
                            selfMxid -> {
                                room.kickUser(friendMxid, REASON)
                                true
                            }
                            else -> false
                        }
                        else -> false
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    // Typically: the friend never joined this room. Nothing to clean.
                    logcat(LogPriority.DEBUG) { "unfriend: skipped room ${room.id()}: ${it.message}" }
                }.getOrDefault(false)
                if (removed) changed++
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return SocialResult.Failure(SocialError.Unknown("unfriend failed: ${t.message}", t))
        } finally {
            friends.remove(friendMxid)
        }
        return SocialResult.Success(changed)
    }

    private companion object {
        const val REASON = "Retiré des amis"
    }
}
