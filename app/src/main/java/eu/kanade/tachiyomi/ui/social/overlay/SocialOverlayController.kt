package eu.kanade.tachiyomi.ui.social.overlay

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.MomentPayload
import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.catalog.SocialThreadCatalog
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * P2-10a: SDK-free controller that drives the social overlay state.
 *
 * Owns the lifecycle of:
 * - the Matrix session (connect/disconnect)
 * - the timelines of every author thread for the current work + episode
 * - the write commands (reply/react/edit/redact/publish)
 * - the UnifiedPush registration
 *
 * Dependencies are interfaces already defined in P2-05..P2-09. The scope is
 * injected so tests can drive coroutines deterministically.
 */
class SocialOverlayController(
    private val session: MatrixSessionManager,
    private val timelineRepo: SocialThreadRepository,
    private val dispatcher: SocialCommandDispatcher,
    private val push: PushRegistrar,
    private val scope: CoroutineScope,
    private val catalog: SocialThreadCatalog? = null,
) {

    private val _state = MutableStateFlow(SocialOverlayState())
    val state: StateFlow<SocialOverlayState> = _state.asStateFlow()

    private var timelineJob: Job? = null
    private var sessionJob: Job? = null

    /** Starts observing the session state. Idempotent. */
    fun connect() {
        if (sessionJob != null) return
        _state.value = _state.value.copy(connection = ConnectionState.Connecting)
        sessionJob = scope.launch {
            session.state.collectLatest { s ->
                _state.value = when (s) {
                    SocialSessionState.Unauthenticated ->
                        _state.value.copy(connection = ConnectionState.Disconnected)
                    is SocialSessionState.Authenticating ->
                        _state.value.copy(connection = ConnectionState.Connecting)
                    is SocialSessionState.Authenticated ->
                        _state.value.copy(connection = ConnectionState.Connected(s.account))
                    is SocialSessionState.Error ->
                        _state.value.copy(connection = ConnectionState.Error(s.error))
                }
            }
        }
    }

    /** Stops observing and resets state. */
    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        timelineJob?.cancel()
        timelineJob = null
        _state.value = SocialOverlayState()
    }

    /** Opens the overlay and starts observing one known [thread]. */
    fun open(thread: ThreadRef) {
        val selfMxid = _state.value.account?.userId
        openThreads(
            descriptors = listOf(
                SocialThreadDescriptor(
                    ref = thread,
                    displayName = null,
                    isOwn = selfMxid == thread.authorMxid,
                ),
            ),
            selected = thread,
        )
    }

    /**
     * Clears the previous work before room/thread bootstrap. Prevents stale
     * threads from another episode being reused when creation fails.
     */
    fun prepareWork() {
        logcat(LogPriority.ERROR) { "SWITCH controller.prepareWork: threads vides" }
        timelineJob?.cancel()
        timelineJob = null
        _state.value = _state.value.copy(
            isOpen = true,
            threads = emptyList(),
            thread = null,
            timeline = null,
            timelines = emptyMap(),
            lastError = null,
            activeRoomId = null,
            activeAuthorMxid = null,
            roomTimeline = null,
        )
    }

    /** Publishes a bootstrap failure to the overlay without opening a thread. */
    fun reportError(error: SocialError) {
        _state.value = _state.value.copy(lastError = error)
    }

    /**
     * Opens every author thread visible for [work] + [episodeKey].
     *
     * The personal thread is always included even when discovery fails, because
     * the caller has already ensured its room and root binding. Friend threads
     * come from [catalog] and keep their own author/room.
     */
    fun openWork(
        work: WorkIdentity,
        episodeKey: EpisodeKey,
        selfMxid: String,
        selfDisplayName: String? = null,
    ) {
        val ownRef = ThreadRef(selfMxid, work.id, episodeKey)
        timelineJob?.cancel()
        _state.value = _state.value.copy(
            isOpen = true,
            threads = emptyList(),
            thread = null,
            timeline = null,
            timelines = emptyMap(),
            lastError = null,
            activeRoomId = null,
            activeAuthorMxid = null,
            roomTimeline = null,
        )

        timelineJob = scope.launch {
            val discovery = try {
                catalog?.discover(work, episodeKey, selfMxid)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                SocialResult.Failure(SocialError.Unknown("Thread discovery failed: ${t.message}", t))
            }
            val discovered = when (discovery) {
                is SocialResult.Success -> discovery.value
                is SocialResult.Failure -> {
                    _state.value = _state.value.copy(lastError = discovery.error)
                    emptyList()
                }
                null -> emptyList()
            }

            val descriptors = buildList {
                discovered.forEach { thread ->
                    add(
                        SocialThreadDescriptor(
                            ref = thread.ref,
                            displayName = thread.displayName,
                            isOwn = thread.isOwn,
                        ),
                    )
                }
                if (none { it.ref == ownRef }) {
                    add(SocialThreadDescriptor(ownRef, selfDisplayName ?: "Moi", isOwn = true))
                }
            }
            val selected = descriptors.firstOrNull { it.isOwn }?.ref ?: descriptors.firstOrNull()?.ref
            logcat(LogPriority.ERROR) { "SWITCH controller.openWork: threads=${descriptors.map { it.ref.authorMxid }} selected=${selected?.authorMxid}" }
            _state.value = _state.value.copy(
                threads = descriptors,
                thread = selected,
                timeline = null,
                timelines = emptyMap(),
            )
            observeThreads(descriptors)
        }
    }

    /**
     * Opens only the current user's thread for [work] + [episodeKey]. Used by
     * the work-level chat, where one room (the user's) contains one thread per
     * episode and friend rooms are not part of that chat surface.
     */
    fun openOwnWork(
        work: WorkIdentity,
        episodeKey: EpisodeKey,
        selfMxid: String,
        selfDisplayName: String? = null,
    ) {
        val ref = ThreadRef(selfMxid, work.id, episodeKey)
        openThreads(
            descriptors = listOf(
                SocialThreadDescriptor(
                    ref = ref,
                    displayName = selfDisplayName ?: "Moi",
                    isOwn = true,
                ),
            ),
            selected = ref,
        )
    }

    /** Opens a room in flat mode (no thread filter). */
    fun openRoom(roomId: String, authorMxid: String) {
        timelineJob?.cancel()
        timelineJob = scope.launch {
            _state.value = _state.value.copy(
                isOpen = true,
                activeRoomId = roomId,
                activeAuthorMxid = authorMxid,
                roomTimeline = null,
                lastError = null,
            )
            timelineRepo.observeRoom(roomId, authorMxid).collect { result ->
                when (result) {
                    is SocialResult.Success -> {
                        _state.value = _state.value.copy(
                            roomTimeline = result.value,
                            lastError = null,
                        )
                    }
                    is SocialResult.Failure -> {
                        _state.value = _state.value.copy(lastError = result.error)
                    }
                }
            }
        }
    }

    /** Selects an already discovered author thread. */
    fun selectThread(thread: ThreadRef) {
        val target = _state.value.threads.find { it.ref.authorMxid == thread.authorMxid }
        if (target == null) {
            logcat(LogPriority.ERROR) { "SWITCH controller.selectThread IGNORE: auteur=${thread.authorMxid} threads=${_state.value.threads.map { it.ref.authorMxid }}" }
            return
        }
        val actualThread = target.ref
        logcat(LogPriority.ERROR) { "SWITCH controller.selectThread OK: auteur=${actualThread.authorMxid} cached=${_state.value.timelines.containsKey(actualThread)}" }
        val cached = _state.value.timelines[actualThread]
        if (cached != null) {
            _state.value = _state.value.copy(
                thread = actualThread,
                timeline = cached,
                lastError = null,
                activeRoomId = null,
                activeAuthorMxid = null,
                roomTimeline = null,
            )
        } else {
            _state.value = _state.value.copy(
                thread = actualThread,
                timeline = null,
                lastError = null,
                activeRoomId = null,
                activeAuthorMxid = null,
                roomTimeline = null,
            )
            scope.launch {
                timelineRepo.observeThread(actualThread).collect { result ->
                    if (result is SocialResult.Success) {
                        val updatedMap = _state.value.timelines.toMutableMap()
                        updatedMap[actualThread] = result.value
                        _state.value = _state.value.copy(
                            timeline = result.value,
                            timelines = updatedMap,
                        )
                    }
                }
            }
        }
    }

    /** Closes the overlay (does not cancel the session). */
    fun close() {
        timelineJob?.cancel()
        timelineJob = null
        _state.value = _state.value.copy(isOpen = false)
    }

    suspend fun reply(body: String, inReplyToEventId: String? = null): SocialResult<String> {
        val roomId = _state.value.activeRoomId
        return if (roomId != null) {
            dispatch(SocialCommand.SendToRoom(roomId, body, inReplyToEventId))
        } else {
            dispatch(SocialCommand.ReplyInAuthorThread(requireThread(), body, inReplyToEventId))
        }
    }

    /** Sends an image or GIF to the open room. */
    suspend fun sendImage(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int,
        height: Int,
        caption: String?,
        inReplyToEventId: String?,
    ): SocialResult<String> {
        val roomId = _state.value.activeRoomId
            ?: return SocialResult.Failure(SocialError.Unknown("Aucun salon ouvert"))
        return dispatch(
            SocialCommand.SendImageToRoom(roomId, bytes, filename, mimeType, width, height, caption, inReplyToEventId),
        )
    }

    suspend fun react(eventId: String, key: String): SocialResult<String> {
        val roomId = _state.value.activeRoomId
        return if (roomId != null) {
            dispatch(SocialCommand.ReactInRoom(roomId, eventId, key))
        } else {
            dispatch(SocialCommand.React(requireThread(), eventId, key))
        }
    }

    suspend fun edit(eventId: String, body: String): SocialResult<String> {
        val roomId = _state.value.activeRoomId
        return if (roomId != null) {
            dispatch(SocialCommand.EditInRoom(roomId, eventId, body))
        } else {
            dispatch(SocialCommand.Edit(requireThread(), eventId, body))
        }
    }

    suspend fun redact(eventId: String, reason: String? = null): SocialResult<String> {
        val roomId = _state.value.activeRoomId
        return if (roomId != null) {
            dispatch(SocialCommand.RedactInRoom(roomId, eventId, reason))
        } else {
            dispatch(SocialCommand.Redact(requireThread(), eventId, reason))
        }
    }

    suspend fun publish(payload: MomentPayload): SocialResult<String> {
        val roomId = _state.value.activeRoomId
        return if (roomId != null) {
            dispatch(SocialCommand.PublishToRoom(roomId, payload))
        } else {
            dispatch(SocialCommand.PublishToMyThread(payload))
        }
    }

    /** P2-10c: restores a persisted session, if any. Safe on fresh install. */
    suspend fun restore(): SocialResult<eu.kanade.tachiyomi.data.social.matrix.SocialAccount?> {
        _state.value = _state.value.copy(connection = ConnectionState.Connecting)
        val result = session.restore()
        if (result is SocialResult.Failure) {
            _state.value = _state.value.copy(
                connection = ConnectionState.Error(result.error),
                lastError = result.error,
            )
        }
        return result
    }

    suspend fun registerPush(endpoint: String): SocialResult<Unit> =
        push.register(endpoint)

    suspend fun unregisterPush(): SocialResult<Unit> =
        push.unregister()

    private fun openThreads(
        descriptors: List<SocialThreadDescriptor>,
        selected: ThreadRef?,
    ) {
        timelineJob?.cancel()
        _state.value = _state.value.copy(
            isOpen = true,
            threads = descriptors,
            thread = selected,
            timeline = null,
            timelines = emptyMap(),
            lastError = null,
            activeRoomId = null,
            activeAuthorMxid = null,
            roomTimeline = null,
        )
        timelineJob = scope.launch {
            observeThreads(descriptors)
        }
    }

    private suspend fun observeThreads(descriptors: List<SocialThreadDescriptor>) = coroutineScope {
        descriptors.forEach { descriptor ->
            launch {
                try {
                    timelineRepo.observeThread(descriptor.ref).collect { result ->
                        when (result) {
                            is SocialResult.Success -> {
                                val timelines = _state.value.timelines + (descriptor.ref to result.value)
                                _state.value = _state.value.copy(
                                    timelines = timelines,
                                    timeline = if (_state.value.thread == descriptor.ref) {
                                        result.value
                                    } else {
                                        _state.value.timeline
                                    },
                                    lastError = null,
                                )
                            }
                            is SocialResult.Failure -> {
                                _state.value = _state.value.copy(lastError = result.error)
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    _state.value = _state.value.copy(
                        lastError = SocialError.Unknown(
                            "Timeline failed for ${descriptor.ref.authorMxid}: ${t.message}",
                            t,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun dispatch(command: SocialCommand): SocialResult<String> {
        if (!_state.value.isConnected) {
            return SocialResult.Failure(
                SocialError.Unknown("Social overlay not connected to a Matrix session"),
            )
        }
        _state.value = _state.value.copy(isSending = true, lastError = null)
        return try {
            val result = dispatcher.execute(command)
            if (result is SocialResult.Failure) {
                _state.value = _state.value.copy(lastError = result.error)
            }
            result
        } finally {
            _state.value = _state.value.copy(isSending = false)
        }
    }

    private fun requireThread(): ThreadRef =
        _state.value.thread
            ?: error("No thread open; call open(thread) first")
}
