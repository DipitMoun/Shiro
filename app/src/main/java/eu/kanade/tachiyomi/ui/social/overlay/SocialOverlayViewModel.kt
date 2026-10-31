package eu.kanade.tachiyomi.ui.social.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import kotlinx.coroutines.flow.StateFlow

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
) : ViewModel() {

    private val controller = SocialOverlayController(
        session = session,
        timelineRepo = timelineRepo,
        dispatcher = dispatcher,
        push = push,
        scope = viewModelScope,
    )

    val state: StateFlow<SocialOverlayState> = controller.state

    fun connect() = controller.connect()
    fun disconnect() = controller.disconnect()
    fun open(thread: ThreadRef) = controller.open(thread)
    fun close() = controller.close()

    override fun onCleared() {
        controller.disconnect()
        super.onCleared()
    }
}
