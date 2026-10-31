package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey

/**
 * In-memory [RoomBindingStore] for unit tests and early bring-up (P2-06).
 * Replaced by a SQLDelight-backed store in P2-07 when the sync layer lands.
 */
class InMemoryRoomBindingStore : RoomBindingStore {

    private val rooms = mutableMapOf<String, String>()
    private val threads = mutableMapOf<String, String>()

    override suspend fun resolveRoom(key: SocialRoomKey): SocialResult<String?> =
        SocialResult.Success(rooms[key.value])

    override suspend fun bindRoom(key: SocialRoomKey, roomId: String): SocialResult<Unit> {
        rooms[key.value] = roomId
        return SocialResult.Success(Unit)
    }

    override suspend fun resolveThreadRoot(key: SocialThreadKey): SocialResult<String?> =
        SocialResult.Success(threads[key.value])

    override suspend fun bindThreadRoot(key: SocialThreadKey, rootEventId: String): SocialResult<Unit> {
        threads[key.value] = rootEventId
        return SocialResult.Success(Unit)
    }
}