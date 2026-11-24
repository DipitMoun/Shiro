package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey

/**
 * Local persistence of the mapping between a social identity and its Matrix
 * identifiers (P2-06).
 *
 * Kept separate from [eu.kanade.tachiyomi.data.social.matrix.session.SessionCredentialStore]:
 * session credentials are per-device secrets, room/thread bindings are shared
 * with the sync layer (P2-07) and will be stored in the SQLDelight database
 * alongside `moment_index`.
 *
 * Implementations must be idempotent and cache-first: `resolve` returns null
 * on a miss (never an error for "not found").
 */
interface RoomBindingStore {
    suspend fun resolveRoom(key: SocialRoomKey): SocialResult<String?>
    suspend fun bindRoom(key: SocialRoomKey, roomId: String): SocialResult<Unit>
    suspend fun resolveThreadRoot(key: SocialThreadKey): SocialResult<String?>
    suspend fun bindThreadRoot(key: SocialThreadKey, rootEventId: String): SocialResult<Unit>

    /** Drops every room and thread binding for the current account. */
    suspend fun clear() {}

    /**
     * Copies the thread bindings of [from] onto [to]. Used only when a legacy
     * row-id based work key is migrated to the stable source+URL key and the
     * same Matrix room is preserved.
     */
    suspend fun migrateRoom(from: SocialRoomKey, to: SocialRoomKey): SocialResult<Unit> =
        SocialResult.Success(Unit)

    /** Every work room bound to [authorMxid]. Used to invite a new friend retroactively. */
    suspend fun roomsForAuthor(authorMxid: String): SocialResult<List<Pair<SocialRoomKey, String>>> =
        SocialResult.Success(emptyList())
}