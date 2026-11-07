package eu.kanade.tachiyomi.ui.social.overlay

import eu.kanade.tachiyomi.data.social.matrix.SocialAccount
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadTimeline
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef

/**
 * P2-10: observable state of the social overlay.
 *
 * Consumed by the Compose overlay. Kept free of SDK types so the controller
 * is unit-testable without the Rust Matrix SDK.
 */
data class SocialOverlayState(
    val isOpen: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Every author thread discovered for the current work + episode. */
    val threads: List<SocialThreadDescriptor> = emptyList(),
    /** Currently selected author thread. */
    val thread: ThreadRef? = null,
    /** Timeline of the selected thread, kept for simple consumers. */
    val timeline: SocialThreadTimeline? = null,
    /** Timelines of all discovered threads, keyed by author thread. */
    val timelines: Map<ThreadRef, SocialThreadTimeline> = emptyMap(),
    val lastError: SocialError? = null,
    val isSending: Boolean = false,
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected
    val account: SocialAccount? get() = (connection as? ConnectionState.Connected)?.account
}

/** UI-facing identity of one author thread; no Matrix SDK type leaks upward. */
data class SocialThreadDescriptor(
    val ref: ThreadRef,
    val displayName: String? = null,
    val isOwn: Boolean = false,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val account: SocialAccount) : ConnectionState
    data class Error(val error: SocialError) : ConnectionState
}
