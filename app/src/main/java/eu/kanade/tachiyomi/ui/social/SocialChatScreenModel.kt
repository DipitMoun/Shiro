package eu.kanade.tachiyomi.ui.social

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.MomentAnchor
import eu.kanade.tachiyomi.data.social.matrix.MomentClient
import eu.kanade.tachiyomi.data.social.matrix.MomentEpisode
import eu.kanade.tachiyomi.data.social.matrix.MomentPayload
import eu.kanade.tachiyomi.data.social.matrix.MomentSource
import eu.kanade.tachiyomi.data.social.matrix.MomentWork
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.friends.SocialFriendInviteAccepter
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.catalog.SocialThreadCatalog
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import eu.kanade.tachiyomi.ui.social.overlay.SocialOverlayController
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Work-level chat: one Matrix room per user + work, one thread per episode.
 *
 * The model opens only the user's thread and switches it when the episode rail
 * changes. Timeline state is cached by the repository, so navigation does not
 * restart the conversation from an empty list.
 */
class SocialChatScreenModel(
    private val roomService: SocialRoomService = Injekt.get(),
    private val session: MatrixSessionManager = Injekt.get(),
    timelineRepo: SocialThreadRepository = Injekt.get(),
    dispatcher: SocialCommandDispatcher = Injekt.get(),
    push: PushRegistrar = Injekt.get(),
    private val catalog: SocialThreadCatalog = Injekt.get(),
    private val avatarLoader: SocialAvatarLoader = Injekt.get(),
    private val friendStore: SocialFriendStore = Injekt.get(),
    private val inviteAccepter: SocialFriendInviteAccepter = Injekt.get(),
) : ScreenModel {

    private val controller = SocialOverlayController(
        session = session,
        timelineRepo = timelineRepo,
        dispatcher = dispatcher,
        push = push,
        scope = screenModelScope,
    )

    val state = controller.state

    private val _friends = MutableStateFlow<List<SocialChatFriend>>(emptyList())
    val friends: StateFlow<List<SocialChatFriend>> = _friends.asStateFlow()

    private val _selfAvatar = MutableStateFlow<ByteArray?>(null)
    val selfAvatar: StateFlow<ByteArray?> = _selfAvatar.asStateFlow()

    private var ensuredRoomForWork = false
    private var ensuredRoomId: String? = null

    init {
        controller.connect()
        screenModelScope.launch {
            if (session.state.value is SocialSessionState.Unauthenticated) {
                controller.restore()
            }
            _selfAvatar.value = avatarLoader.loadOwnAvatar()
            inviteAccepter.acceptInvitesFromFriends()
        }
    }

    fun selectWork(
        context: SocialChatContext,
        selfMxid: String,
        selfDisplayName: String?,
    ) {
        screenModelScope.launch {
            logcat(LogPriority.ERROR) { "SWITCH model.selectWork: work=${context.work.id.value}" }
            controller.prepareWork()
            val roomId = ensureRoom(context, selfMxid, selfDisplayName) ?: return@launch
            controller.openRoom(roomId, selfMxid)
            refreshFriends(context)
        }
    }

    /** Opens the exact room carried by a notification, without ensuring a new one. */
    fun openRoomDirect(
        roomId: String,
        authorMxid: String,
        context: SocialChatContext,
    ) {
        screenModelScope.launch {
            controller.prepareWork()
            controller.openRoom(roomId, authorMxid)
            refreshFriends(context)
        }
    }

    fun send(
        text: String,
        positions: List<Long>,
        episode: SocialChatEpisode,
        context: SocialChatContext,
        inReplyToEventId: String? = null,
    ) {
        val body = text.trim()
        if (body.isEmpty() && positions.isEmpty()) return
        screenModelScope.launch {
            if (positions.isEmpty()) {
                controller.reply(body, inReplyToEventId)
            } else {
                controller.publish(
                    buildMomentPayload(
                        context = context,
                        episode = episode,
                        note = body,
                        positions = positions,
                    ),
                )
            }
        }
    }

    fun react(eventId: String, key: String) {
        screenModelScope.launch { controller.react(eventId, key) }
    }

    /** Switches the chat view to the flat room of [participant]. */
    fun selectSalon(participant: SocialChatFriend, context: SocialChatContext) {
        screenModelScope.launch {
            val result = catalog.discoverRoomsFor(participant.mxid)
            val room = (result as? SocialResult.Success)
                ?.value
                ?.firstOrNull { it.workId == context.work.id }
            if (room != null) {
                logcat(LogPriority.ERROR) { "SWITCH room: ${participant.mxid} -> ${room.roomId}" }
                controller.openRoom(room.roomId, participant.mxid)
            } else {
                logcat(LogPriority.ERROR) { "SWITCH room: ${participant.mxid} ABSENT" }
                controller.reportError(SocialError.Unknown("Pas encore de salon pour ${participant.name}"))
            }
        }
    }

    /** Switches the chat view to the thread owned by [ref.authorMxid]. */
    fun selectThread(ref: ThreadRef) {
        logcat(LogPriority.ERROR) { "SWITCH model.selectThread: auteur=${ref.authorMxid}" }
        controller.selectThread(ref)
    }

    private suspend fun refreshFriends(context: SocialChatContext) {
        if (_selfAvatar.value == null) {
            _selfAvatar.value = avatarLoader.loadOwnAvatar()
        }
        val friends = mutableListOf<SocialChatFriend>()
        friendStore.friends.value.forEach { record ->
            val name = record.displayName
                ?: record.mxid.substringBefore(':').removePrefix("@")
            val avatar = avatarLoader.loadProfileAvatar(record.mxid)
            val rooms = catalog.discoverRoomsFor(record.mxid)
            val hasRoom = rooms is SocialResult.Success &&
                rooms.value.any { it.workId == context.work.id }
            friends += SocialChatFriend(
                mxid = record.mxid,
                name = name,
                avatarBytes = avatar,
                hasRoom = hasRoom,
            )
        }
        _friends.value = friends
    }

    private fun buildMomentPayload(
        context: SocialChatContext,
        episode: SocialChatEpisode,
        note: String,
        positions: List<Long>,
    ): MomentPayload {
        if (context.medium == SocialChatMedium.MANGA) {
            return MomentPayload(
                v = MomentPayload.PAGE_VERSION,
                work = MomentWork(
                    id = context.work.id.value,
                    ids = context.work.ids,
                    title = context.work.title,
                    format = "MANGA",
                ),
                episode = MomentEpisode(
                    key = episode.key.value,
                    number = episode.number,
                    kind = "regular",
                    numbering = "unknown",
                    title = episode.title,
                ),
                moments = positions
                    .map { it.toInt().coerceIn(1, 9999) }
                    .distinct()
                    .take(5)
                    .map { page -> MomentAnchor(page = page) },
                note = note.takeIf { it.isNotBlank() },
                source = MomentSource(key = context.work.id.value, kind = "reader"),
                capturedAt = Instant.now().toString(),
                client = MomentClient("Shiro", BuildConfig.VERSION_NAME),
            )
        }
        val durationMs = episode.durationSeconds.takeIf { it > 0L }?.times(1000L)
        return MomentPayload(
            v = MomentPayload.VERSION,
            work = MomentWork(
                id = context.work.id.value,
                ids = context.work.ids,
                title = context.work.title,
            ),
            episode = MomentEpisode(
                key = episode.key.value,
                number = episode.number,
                season = null,
                kind = "regular",
                numbering = "unknown",
                title = episode.title,
            ),
            moments = positions
                .distinct()
                .take(5)
                .map { positionMs ->
                    MomentAnchor(
                        positionMs = positionMs.coerceAtLeast(0L),
                        beforeMs = 15_000L,
                        afterMs = 15_000L,
                    )
                },
            note = note.takeIf { it.isNotBlank() },
            source = MomentSource(
                key = context.work.id.value,
                kind = "local",
                durationMs = durationMs,
            ),
            capturedAt = Instant.now().toString(),
            client = MomentClient("Shiro", BuildConfig.VERSION_NAME),
        )
    }

    private suspend fun ensureRoom(
        context: SocialChatContext,
        selfMxid: String,
        selfDisplayName: String?,
    ): String? {
        if (ensuredRoomForWork) return ensuredRoomId
        val result = roomService.ensureRoom(
            authorMxid = selfMxid,
            authorDisplayName = selfDisplayName,
            selfMxid = selfMxid,
            work = context.work,
            friends = friendStore.asAudienceFriends(),
        )
        return when (result) {
            is SocialResult.Success -> {
                ensuredRoomForWork = true
                ensuredRoomId = result.value.roomId
                result.value.roomId
            }
            is SocialResult.Failure -> {
                controller.reportError(result.error)
                null
            }
        }
    }
}
