package eu.kanade.tachiyomi.ui.browse.telegram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.telegram.TelegramChannelSelectionScreen
import eu.kanade.presentation.util.Screen

/**
 * Écran plein écran de sélection des canaux (vidéo / document).
 */
class TelegramSelectChannelsScreen(
    private val model: TelegramScreenModel,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val state by model.state.collectAsState()

        TelegramChannelSelectionScreen(
            state = state,
            navigateUp = navigator::pop,
            onConfirm = { videoChats, docChats ->
                model.saveSelection(videoChats, docChats)
                navigator.pop()
            },
            onSendPhone = model::sendPhoneNumber,
            onSendCode = model::sendCode,
            onCheckPassword = model::checkPassword,
            onResendCode = model::resendCode,
            onRetry = model::retry,
            onLoadChats = model::loadChats,
        )
    }
}
