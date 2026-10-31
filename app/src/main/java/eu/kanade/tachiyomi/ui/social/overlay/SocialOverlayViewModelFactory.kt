package eu.kanade.tachiyomi.ui.social.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadRepository
import eu.kanade.tachiyomi.data.social.matrix.push.PushRegistrar
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * P2-10b: factory that resolves the [SocialOverlayViewModel] dependencies
 * from Injekt. Kept separate so [SocialOverlayViewModel] stays free of Injekt
 * imports (easier to unit-test).
 */
class SocialOverlayViewModelFactory : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val session: MatrixSessionManager = Injekt.get()
        val timelineRepo: SocialThreadRepository = Injekt.get()
        val dispatcher: SocialCommandDispatcher = Injekt.get()
        val push: PushRegistrar = Injekt.get()
        return SocialOverlayViewModel(
            session = session,
            timelineRepo = timelineRepo,
            dispatcher = dispatcher,
            push = push,
        ) as T
    }
}
