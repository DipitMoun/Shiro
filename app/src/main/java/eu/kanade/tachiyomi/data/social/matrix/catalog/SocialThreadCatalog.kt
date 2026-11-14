package eu.kanade.tachiyomi.data.social.matrix.catalog

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialSchemaKeys
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.room.RoomBindingStore
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** One author thread that the current account can read for a work + episode. */
data class DiscoveredThread(
    val ref: ThreadRef,
    val displayName: String?,
    val avatarUrl: String?,
    val roomId: String,
    val threadRootEventId: String,
    val isOwn: Boolean,
)

/**
 * Read-side discovery of author threads for the current work + episode.
 *
 * The overlay needs to show every friend room the user has joined, not only the
 * locally bootstrapped personal room. Implementations resolve the room/thread
 * bindings before returning, so [ThreadRef] stays usable by the existing
 * [eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository].
 */
interface SocialThreadCatalog {
    suspend fun discover(
        work: WorkIdentity,
        episodeKey: EpisodeKey,
        selfMxid: String,
    ): SocialResult<List<DiscoveredThread>>
}

/**
 * Production catalog backed by the joined Matrix rooms.
 *
 * Discovery is deliberately conservative: a room is included only when its
 * topic is the exact `Shiro · <workId> · fil de <owner>` form, its membership is
 * joined, and a matching episode thread root is either bound locally or visible
 * through the SDK thread list. Rooms that do not match are ignored.
 */
class RustSocialThreadCatalog(
    private val clientProvider: () -> Client?,
    private val bindings: RoomBindingStore,
) : SocialThreadCatalog {

    override suspend fun discover(
        work: WorkIdentity,
        episodeKey: EpisodeKey,
        selfMxid: String,
    ): SocialResult<List<DiscoveredThread>> {
        val client = clientProvider() ?: return SocialResult.Failure(
            SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
        )

        val rooms = try {
            client.rooms()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return SocialResult.Failure(SocialError.Unknown("rooms() failed: ${t.message}", t))
        }

        val discovered = mutableListOf<DiscoveredThread>()
        for (room in rooms) {
            val info = try {
                room.roomInfo()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                android.util.Log.w("SocialCatalog", "roomInfo failed for ${room.id()}: ${t.message}")
                continue
            }
            if (info.membership != Membership.JOINED) continue

            val roomKey = SocialRoomKey.parseTopic(info.topic ?: continue) ?: continue
            if (roomKey.workId != work.id) continue

            val threadKey = SocialThreadKey(roomKey, episodeKey)
            val rootId = resolveRoot(room, threadKey)
            if (rootId == null) {
                android.util.Log.d(
                    "SocialCatalog",
                    "no ${episodeKey.value} thread in ${roomKey.value}",
                )
                continue
            }

            val displayName = try {
                room.memberDisplayName(roomKey.authorMxid)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                null
            } ?: roomKey.authorMxid.substringBefore(':').removePrefix("@")
            val avatarUrl = try {
                room.memberAvatarUrl(roomKey.authorMxid)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                null
            }

            discovered += DiscoveredThread(
                ref = ThreadRef(roomKey.authorMxid, roomKey.workId, episodeKey),
                displayName = displayName,
                avatarUrl = avatarUrl,
                roomId = room.id(),
                threadRootEventId = rootId,
                isOwn = roomKey.authorMxid == selfMxid,
            )
        }

        return SocialResult.Success(discovered)
    }

    private suspend fun resolveRoot(room: Room, threadKey: SocialThreadKey): String? {
        when (val cached = bindings.resolveThreadRoot(threadKey)) {
            is SocialResult.Success -> cached.value?.let { return it }
            is SocialResult.Failure -> {
                android.util.Log.w("SocialCatalog", "thread binding lookup failed: ${cached.error}")
                return null
            }
        }

        val service = try {
            room.threadListService()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            android.util.Log.w("SocialCatalog", "threadListService failed: ${t.message}")
            return null
        }

        return try {
            service.paginate()
            val rootId = service.items()
                .asSequence()
                .mapNotNull { it.rootEvent }
                .firstOrNull { item -> item.matchesEpisode(threadKey.episodeKey) }
                ?.eventId
                ?: return null

            when (val bound = bindings.bindThreadRoot(threadKey, rootId)) {
                is SocialResult.Success -> rootId
                is SocialResult.Failure -> {
                    android.util.Log.w("SocialCatalog", "thread binding failed: ${bound.error}")
                    null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            android.util.Log.w("SocialCatalog", "thread list failed: ${t.message}")
            null
        } finally {
            try {
                service.close()
            } catch (_: Throwable) {
                // Best effort: the SDK owns the native handle.
            }
        }
    }

    private fun org.matrix.rustcomponents.sdk.ThreadListItemEvent.matchesEpisode(
        episodeKey: EpisodeKey,
    ): Boolean {
        val content = content as? TimelineItemContent.MsgLike ?: return false
        val message = content.content.kind as? MsgLikeKind.Message ?: return false
        return SocialSchemaKeys.threadRootEpisodeKey(message.content.body) == episodeKey
    }
}
