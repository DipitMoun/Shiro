package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * Scriptable [MatrixRoomTransport] for [SocialRoomServiceTest].
 * Tracks every call so the orchestration can be asserted.
 */
class FakeMatrixRoomTransport : MatrixRoomTransport {

    var createRoomResult: SocialResult<String> = SocialResult.Success("!room0:example.org")
    var sendMessageResult: SocialResult<String> = SocialResult.Success("root0")
    var sendStateEventResult: SocialResult<String> = SocialResult.Success("root0")
    var inviteResult: SocialResult<Unit> = SocialResult.Success(Unit)
    var roomCreatedAtResult: SocialResult<Long?> = SocialResult.Success(null)
    var threadRootBodiesResult: SocialResult<List<String>> = SocialResult.Success(emptyList())

    var createRoomCalls: Int = 0; private set
    var sendMessageCalls: Int = 0; private set
    var sendStateEventCalls: Int = 0; private set
    val inviteCalls: MutableList<String> = mutableListOf()

    var lastCreateRoomName: String? = null; private set
    var lastCreateRoomTopic: String? = null; private set
    var lastCreateRoomState: String? = null; private set
    var lastCreateRoomInvitees: List<String> = emptyList(); private set
    var lastSendMessageContent: String? = null; private set
    var lastSendStateType: String? = null; private set
    var lastSendStateContent: String? = null; private set

    override suspend fun createRoom(
        name: String,
        topic: String,
        initialStateJson: String,
        invitees: List<String>,
    ): SocialResult<String> {
        createRoomCalls++
        lastCreateRoomName = name
        lastCreateRoomTopic = topic
        lastCreateRoomState = initialStateJson
        lastCreateRoomInvitees = invitees
        return createRoomResult
    }

    override suspend fun sendMessage(
        roomId: String,
        contentJson: String,
    ): SocialResult<String> {
        sendMessageCalls++
        lastSendMessageContent = contentJson
        return sendMessageResult
    }

    override suspend fun sendStateEvent(
        roomId: String,
        type: String,
        contentJson: String,
    ): SocialResult<String> {
        sendStateEventCalls++
        lastSendStateType = type
        lastSendStateContent = contentJson
        return sendStateEventResult
    }

    override suspend fun invite(roomId: String, inviteeMxid: String): SocialResult<Unit> {
        inviteCalls += inviteeMxid
        return inviteResult
    }

    override suspend fun roomCreatedAt(roomId: String): SocialResult<Long?> = roomCreatedAtResult

    override suspend fun threadRootBodies(roomId: String): SocialResult<List<String>> =
        threadRootBodiesResult

    fun failWith(error: SocialError) {
        createRoomResult = SocialResult.Failure(error)
        sendMessageResult = SocialResult.Failure(error)
        sendStateEventResult = SocialResult.Failure(error)
        inviteResult = SocialResult.Failure(error)
    }
}