package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

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

            // Send the initial org.shiro.room state via the freshly joined Room.
            val room = client.getRoom(roomId)
                ?: return SocialResult.Failure(
                    SocialError.Unknown("Room $roomId not found after creation"),
                )
            room.sendStateEventRaw("org.shiro.room", "", initialStateJson, null)

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
            val room = client.getRoom(roomId)
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
            val room = client.getRoom(roomId)
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
            val room = client.getRoom(roomId)
                ?: return SocialResult.Failure(SocialError.Unknown("Room $roomId not found in store"))
            room.inviteUserById(inviteeMxid)
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "invite($inviteeMxid)"))
        }
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