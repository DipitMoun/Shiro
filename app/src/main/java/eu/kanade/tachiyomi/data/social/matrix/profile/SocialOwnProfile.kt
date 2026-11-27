package eu.kanade.tachiyomi.data.social.matrix.profile

import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * The connected user's avatar, shared by the bottom bar and the Social page.
 *
 * Reloaded when the account changes and after an avatar upload ([refresh]).
 */
class SocialOwnProfile(
    private val session: MatrixSessionManager,
    private val avatarLoader: SocialAvatarLoader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _avatar = MutableStateFlow<ByteArray?>(null)

    /** Encoded image bytes, or null when logged out or without an avatar. */
    val avatar: StateFlow<ByteArray?> = _avatar.asStateFlow()

    private val _displayName = MutableStateFlow<String?>(null)

    /** Display name from the Matrix profile; the session does not always carry it. */
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    init {
        scope.launch {
            session.state
                .distinctUntilChangedBy { (it as? SocialSessionState.Authenticated)?.account?.userId }
                .collect { state ->
                    if (state is SocialSessionState.Authenticated) {
                        load()
                    } else {
                        _avatar.value = null
                        _displayName.value = null
                    }
                }
        }
    }

    /** The name just saved, shown before the SDK catches up with it. */
    fun overrideDisplayName(name: String) {
        _displayName.value = name
    }

    fun refresh() {
        scope.launch { load() }
    }

    private suspend fun load() {
        _avatar.value = runCatching { avatarLoader.loadOwnAvatar(AVATAR_SIZE) }.getOrNull()
        _displayName.value = runCatching { session.clientOrNull()?.displayName() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val AVATAR_SIZE = 192
    }
}
