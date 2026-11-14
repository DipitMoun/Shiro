package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.TimelineItemContent
import uniffi.ruma_events.StateEventType

/**
 * P2-06b: production [MatrixRoomTransport] wired to the Rust Matrix SDK.
 *
 * [clientProvider] returns the [Client] owned by
 * [eu.kanade.tachiyomi.data.social.matrix.session.RustSessionDelegate] while a
 * session is active, and null otherwise. Returning null surfaces
 * [SocialError.Auth] to the caller.
 *
 * Matrix specifics (PowerLevels, RoomVisibility, JoinRule, ...) stay in this file.
 * The rest of the social layer depends on the SDK-free [MatrixRoomTransport].
 */
class RustMatrixRoomTransport(
    private val clientProvider: () -> Client?,
) : MatrixRoomTransport {

    override suspend fun createRoom(
        name: String,
        topic: String,
        initialStateJson: String,
        invitees: List<String>,
    ): SocialResult<String> {
        val client = clientProvider() ?: return notAuthenticated()

        return try {
            val params = CreateRoomParameters(
                name = name,
                topic = topic,
                isDirect = false,
                isEncrypted = true,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
                invite = invitees,
                avatar = null,
                powerLevelContentOverride = defaultPowerLevels(),
                joinRuleOverride = JoinRule.Invite,
                historyVisibilityOverride = RoomHistoryVisibility.Shared,
                canonicalAlias = null,
                isSpace = false,
            )
            // createRoom resolves to the new room id (String), not a Room object.
            val roomId: String = client.createRoom(params)

            // The local store may not expose the room immediately. Do not fail
            // the whole creation if the optional metadata event cannot be sent:
            // the room itself exists and the id is what the binding needs.
            val room = awaitRoom(client, roomId)
            if (room != null) {
                try {
                    room.sendStateEventRaw("org.shiro.room", "", initialStateJson, null)
                } catch (t: Throwable) {
                    android.util.Log.e(
                        "SocialOverlay",
                        "createRoom: org.shiro.room state failed (room kept)",
                        t,
                    )
                }
            } else {
                android.util.Log.w(
                    "SocialOverlay",
                    "createRoom: room $roomId not in local store yet",
                )
            }

            SocialResult.Success(roomId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "createRoom"))
        }
    }

    override suspend fun sendMessage(
        roomId: String,
        contentJson: String,
    ): SocialResult<String> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = awaitRoom(client, roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found"))
            val eventId = room.sendRaw("m.room.message", contentJson, null)
            SocialResult.Success(eventId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "sendMessage"))
        }
    }

    override suspend fun sendStateEvent(
        roomId: String,
        type: String,
        contentJson: String,
    ): SocialResult<String> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = awaitRoom(client, roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found in store"))
            val eventId = room.sendStateEventRaw(type, "", contentJson, null)
            SocialResult.Success(eventId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "sendStateEvent($type)"))
        }
    }

    override suspend fun invite(roomId: String, inviteeMxid: String): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = awaitRoom(client, roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found in store"))
            room.inviteUserById(inviteeMxid)
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "invite($inviteeMxid)"))
        }
    }

    override suspend fun roomCreatedAt(roomId: String): SocialResult<Long?> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = awaitRoom(client, roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found in store"))
            val createdAt = room.stateEvents(StateEventType.RoomCreate)
                .firstOrNull()
                ?.timestamp
                ?.toLong()
            SocialResult.Success(createdAt)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "roomCreatedAt"))
        }
    }

    override suspend fun threadRootBodies(roomId: String): SocialResult<List<String>> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = awaitRoom(client, roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found in store"))
            val service = room.threadListService()
            try {
                service.paginate()
                val bodies = service.items().mapNotNull { item ->
                    val content = item.rootEvent.content as? TimelineItemContent.MsgLike
                    val message = content?.content?.kind as? MsgLikeKind.Message
                    message?.content?.body
                }
                SocialResult.Success(bodies)
            } finally {
                try { service.close() } catch (_: Throwable) {}
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "threadRootBodies"))
        }
    }

    override suspend fun updateRoomMetadata(
        roomId: String,
        name: String,
        topic: String,
    ): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()
        return try {
            val room = awaitRoom(client, roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found in store"))
            room.setName(name)
            room.setTopic(topic)
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "updateRoomMetadata"))
        }
    }

    /**
     * The SDK can return a room id before the room is visible in the local
     * store. Wait briefly so the immediate follow-up (state event, thread root)
     * does not fail spuriously.
     */
    private suspend fun awaitRoom(client: Client, roomId: String): Room? {
        repeat(20) {
            client.getRoom(roomId)?.let { return it }
            delay(100)
        }
        return client.getRoom(roomId)
    }

    private fun <T> notAuthenticated(): SocialResult<T> = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )

    private fun defaultPowerLevels(): PowerLevels = PowerLevels(
        usersDefault = 0,
        eventsDefault = 0,
        stateDefault = 50,
        ban = 50,
        kick = 50,
        redact = 50,
        invite = 100,
        notifications = null,
        events = mapOf(
            "org.shiro.room" to 100,
            "org.shiro.pins" to 100,
            "m.room.encryption" to 100,
            "m.room.name" to 50,
            "m.room.topic" to 50,
            "m.room.avatar" to 50,
        ),
        users = emptyMap(),
    )

    private fun mapError(t: Throwable, op: String): SocialError {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            "unauthor" in lower || "forbidden" in lower || "m.forbidden" in lower ->
                SocialError.Permission(msg)
            "network" in lower || "connect" in lower || "timed out" in lower || "dns" in lower ->
                SocialError.Network(transient = true, message = msg, cause = t)
            "rate" in lower || "too many" in lower ->
                SocialError.RateLimit(retryAfterMs = null)
            else -> SocialError.Unknown("$op failed: $msg", t)
        }
    }
}