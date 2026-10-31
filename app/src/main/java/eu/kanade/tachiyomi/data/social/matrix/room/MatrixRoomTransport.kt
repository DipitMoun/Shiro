package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * P2-06: room-level Matrix operations the social layer needs on top of
 * [eu.kanade.tachiyomi.data.social.matrix.MatrixTransport] (which only does
 * sync/send/redact/react).
 *
 * Follows the same conventions: every call is suspend and returns [SocialResult];
 * SDK errors are mapped by the implementation, not thrown.
 *
 * Room creation is idempotent from the caller's perspective: [SocialRoomService]
 * checks its [RoomBindingStore] before calling [createRoom], and the Matrix
 * homeserver rejects duplicates through the deterministic room name (P2-06b).
 */
interface MatrixRoomTransport {

    /**
     * Creates an encrypted, invite-only room for one (author, work) pair.
     *
     * [name] and [topic] come from [eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey].
     * [initialStateJson] carries the `org.shiro.room` payload. [invitees] are the
     * mxids to invite right after creation.
     *
     * Resolves to the new room id (`!xxx:server`).
     */
    suspend fun createRoom(
        name: String,
        topic: String,
        initialStateJson: String,
        invitees: List<String>,
    ): SocialResult<String>

    /**
     * Sends a state event with the given [type] and empty state key.
     * Used for `org.shiro.room` (already sent at creation) and any future
     * room-level metadata update.
     */
    suspend fun sendStateEvent(
        roomId: String,
        type: String,
        contentJson: String,
    ): SocialResult<String>

    /**
     * Invites one mxid to [roomId]. Resolves to `Unit`; idempotent on the server.
     * Called for each [eu.kanade.tachiyomi.data.social.audience.AudiencePlan.invited].
     */
    suspend fun invite(roomId: String, inviteeMxid: String): SocialResult<Unit>
}