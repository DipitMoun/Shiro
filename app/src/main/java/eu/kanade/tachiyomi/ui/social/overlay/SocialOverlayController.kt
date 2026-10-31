package eu.kanade.tachiyomi.ui.social.overlay

import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.MomentPayload
import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * P2-10a: SDK-free controller that drives the social overlay state.
 *
 * Owns the lifecycle of:
 * - the Matrix session (connect/disconnect)
 * - the timeline of one thread (open/close)
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

    /** Opens the overlay and starts observing [thread]. */
    fun open(thread: ThreadRef) {
        timelineJob?.cancel()
        _state.value = _state.value.copy(
            isOpen = true,
            thread = thread,
            lastError = null,
        )
        timelineJob = scope.launch {
            timelineRepo.observeThread(thread).collectLatest { result ->
                _state.value = when (result) {
                    is SocialResult.Success ->
                        _state.value.copy(timeline = result.value, lastError = null)
                    is SocialResult.Failure ->
                        _state.value.copy(lastError = result.error)
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

    suspend fun reply(body: String): SocialResult<String> {
        val thread = requireThread()
        return dispatch(SocialCommand.ReplyInAuthorThread(thread, body))
    }

    suspend fun react(eventId: String, key: String): SocialResult<String> {
        val thread = requireThread()
        return dispatch(SocialCommand.React(thread, eventId, key))
    }

    suspend fun edit(eventId: String, body: String): SocialResult<String> {
        val thread = requireThread()
        return dispatch(SocialCommand.Edit(thread, eventId, body))
    }

    suspend fun redact(eventId: String, reason: String? = null): SocialResult<String> {
        val thread = requireThread()
        return dispatch(SocialCommand.Redact(thread, eventId, reason))
    }

    suspend fun publish(payload: MomentPayload): SocialResult<String> =
        dispatch(SocialCommand.PublishToMyThread(payload))

    suspend fun registerPush(endpoint: String): SocialResult<Unit> =
        push.register(endpoint)

    suspend fun unregisterPush(): SocialResult<Unit> =
        push.unregister()

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
