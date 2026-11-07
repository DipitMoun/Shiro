package eu.kanade.tachiyomi.ui.social.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.MomentPayload
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.catalog.SocialThreadCatalog
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import eu.kanade.tachiyomi.data.social.matrix.room.SocialRoomService
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * P2-10b: Android [ViewModel] wrapper around [SocialOverlayController].
 *
 * The controller is constructed with [viewModelScope], so its internal jobs
 * are cancelled automatically when the host Activity is destroyed.
 *
 * Activity usage:
 *   val socialViewModel by viewModels<SocialOverlayViewModel>(factoryProducer = { SocialOverlayViewModelFactory() })
 *   // inside setContent:
 *   val socialState by socialViewModel.state.collectAsState()
 */
class SocialOverlayViewModel(
    session: MatrixSessionManager,
    timelineRepo: SocialThreadRepository,
    dispatcher: SocialCommandDispatcher,
    push: PushRegistrar,
    roomService: SocialRoomService,
    catalog: SocialThreadCatalog,
) : ViewModel() {

    private val roomService: SocialRoomService = roomService

    private val controller = SocialOverlayController(
        session = session,
        timelineRepo = timelineRepo,
        dispatcher = dispatcher,
        push = push,
        scope = viewModelScope,
        catalog = catalog,
    )

    val state: StateFlow<SocialOverlayState> = controller.state

    fun connect() = controller.connect()

    fun react(eventId: String, key: String) {
        viewModelScope.launch { controller.react(eventId, key) }
    }

    fun send(text: String, publish: Boolean, payload: MomentPayload?) {
        viewModelScope.launch {
            if (publish) {
                if (payload == null) {
                    android.util.Log.e("SocialOverlay", "send: publish requested without a payload")
                    return@launch
                }
                controller.publish(payload)
            } else {
                controller.reply(text)
            }
        }
    }

    fun selectThread(thread: ThreadRef) = controller.selectThread(thread)

    fun bootstrap(
        mxid: String,
        work: WorkIdentity,
        episodeKey: String,
        workTitle: String?,
        selfDisplayName: String? = null,
    ) {
        viewModelScope.launch {
            controller.prepareWork()
            val epKey = EpisodeKey.parse(episodeKey)
            if (epKey == null) {
                android.util.Log.e("SocialOverlay", "bootstrap: EpisodeKey.parse failed for $episodeKey")
                controller.reportError(
                    eu.kanade.tachiyomi.data.social.matrix.SocialError.Unknown(
                        "Invalid episode key: $episodeKey",
                    ),
                )
                return@launch
            }

            android.util.Log.d("SocialOverlay", "bootstrap: ensureRoom work=${work.id.value}")
            val roomResult = roomService.ensureRoom(
                authorMxid = mxid,
                authorDisplayName = selfDisplayName,
                selfMxid = mxid,
                work = work,
                friends = emptyList(),
            )
            if (roomResult is eu.kanade.tachiyomi.data.social.matrix.SocialResult.Failure) {
                android.util.Log.e("SocialOverlay", "bootstrap: ensureRoom FAILED: " + roomResult.error.toString())
                controller.reportError(roomResult.error)
                return@launch
            }
            val ensured = (roomResult as eu.kanade.tachiyomi.data.social.matrix.SocialResult.Success).value
            android.util.Log.d("SocialOverlay", "bootstrap: roomId=${ensured.roomId} created=${ensured.created}")

            val roomKey = SocialRoomKey(authorMxid = mxid, workId = work.id)
            val threadResult = roomService.ensureThreadRoot(roomKey, epKey.value, workTitle)
            if (threadResult is eu.kanade.tachiyomi.data.social.matrix.SocialResult.Failure) {
                android.util.Log.e("SocialOverlay", "bootstrap: ensureThreadRoot FAILED: " + threadResult.error.toString())
                controller.reportError(threadResult.error)
                return@launch
            }
            val rootId = (threadResult as eu.kanade.tachiyomi.data.social.matrix.SocialResult.Success).value
            android.util.Log.d("SocialOverlay", "bootstrap: threadRoot=$rootId")

            android.util.Log.d("SocialOverlay", "bootstrap: opening threads")
            controller.openWork(
                work = work,
                episodeKey = epKey,
                selfMxid = mxid,
                selfDisplayName = selfDisplayName,
            )
        }
    }

    fun restore() {
        viewModelScope.launch { controller.restore() }
    }
    fun disconnect() = controller.disconnect()
    fun open(thread: ThreadRef) = controller.open(thread)
    fun close() = controller.close()

    override fun onCleared() {
        controller.disconnect()
        super.onCleared()
    }
}
