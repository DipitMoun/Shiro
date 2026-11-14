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
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.friends.SocialFriendInviteAccepter
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.catalog.SocialThreadCatalog
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import eu.kanade.tachiyomi.ui.social.overlay.SocialOverlayController
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

    fun selectEpisode(
        context: SocialChatContext,
        selfMxid: String,
        selfDisplayName: String?,
        episode: SocialChatEpisode,
    ) {
        screenModelScope.launch {
            controller.prepareWork()
            if (!ensureRoom(context, selfMxid, selfDisplayName)) return@launch
            openEpisode(context, selfMxid, selfDisplayName, episode)
            refreshFriends(context, selfMxid, episode)
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
        if (state.value.thread == null) return
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
        if (state.value.thread == null) return
        screenModelScope.launch { controller.react(eventId, key) }
    }

    private suspend fun refreshFriends(
        context: SocialChatContext,
        selfMxid: String,
        episode: SocialChatEpisode,
    ) {
        if (_selfAvatar.value == null) {
            _selfAvatar.value = avatarLoader.loadOwnAvatar()
        }
        val discovered = when (val result = catalog.discover(context.work, episode.key, selfMxid)) {
            is SocialResult.Success -> result.value.associateBy { it.ref.authorMxid }
            is SocialResult.Failure -> emptyMap()
        }
        val friends = mutableListOf<SocialChatFriend>()
        friendStore.friends.value.forEach { record ->
            val thread = discovered[record.mxid]
            val name = record.displayName
                ?: thread?.displayName
                ?: record.mxid.substringBefore(':').removePrefix("@")
            val avatar = avatarLoader.loadProfileAvatar(record.mxid)
                ?: thread?.avatarUrl?.let { avatarLoader.loadAvatar(it) }
            friends += SocialChatFriend(
                mxid = record.mxid,
                name = name,
                avatarBytes = avatar,
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
    ): Boolean {
        if (ensuredRoomForWork) return true
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
                true
            }
            is SocialResult.Failure -> {
                controller.reportError(result.error)
                false
            }
        }
    }

    private suspend fun openEpisode(
        context: SocialChatContext,
        selfMxid: String,
        selfDisplayName: String?,
        episode: SocialChatEpisode,
    ) {
        val roomKey = SocialRoomKey(authorMxid = selfMxid, workId = context.work.id)
        when (val root = roomService.ensureThreadRoot(roomKey, episode.key.value, context.title)) {
            is SocialResult.Success -> {
                controller.openOwnWork(
                    work = context.work,
                    episodeKey = episode.key,
                    selfMxid = selfMxid,
                    selfDisplayName = selfDisplayName,
                )
            }
            is SocialResult.Failure -> controller.reportError(root.error)
        }
    }
}
