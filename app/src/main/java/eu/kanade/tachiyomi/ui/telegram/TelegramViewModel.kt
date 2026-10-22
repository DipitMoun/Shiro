package eu.kanade.tachiyomi.ui.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.telegram.TelegramChat
import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthManager
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramChannelScraper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

class TelegramViewModel : ViewModel() {
    private val authManager: TelegramAuthManager by injectLazy()
    private val scraper: TelegramChannelScraper by injectLazy()
    private val preferences: TelegramPreferences by injectLazy()

    val authState: StateFlow<TelegramAuthState> = authManager.authState

    private val _chats = MutableStateFlow<List<TelegramChat>>(emptyList())
    val chats: StateFlow<List<TelegramChat>> = _chats.asStateFlow()

    private val _isLoadingChats = MutableStateFlow(false)
    val isLoadingChats: StateFlow<Boolean> = _isLoadingChats.asStateFlow()

    init {
        authManager.start()
    }

    fun sendPhoneNumber(phone: String) = authManager.sendPhoneNumber(phone)
    fun sendAuthCode(code: String) = authManager.sendAuthCode(code)
    fun checkPassword(password: String) = authManager.checkPassword(password)

    fun loadChats() {
        viewModelScope.launch {
            _isLoadingChats.value = true
            try {
                val result = scraper.getUserChats()
                _chats.value = result.map { TelegramChat(it.id, it.title) }
            } catch (e: Exception) {
                _chats.value = emptyList()
            } finally {
                _isLoadingChats.value = false
            }
        }
    }

    fun saveVideoChats(ids: Set<Long>) {
        preferences.videoChats = ids
    }
    fun saveDocChats(ids: Set<Long>) {
        preferences.docChats = ids
    }
}
