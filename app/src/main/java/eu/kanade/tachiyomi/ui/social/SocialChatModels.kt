package eu.kanade.tachiyomi.ui.social

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity

/**
 * One episode available as a thread inside the work chat.
 * [id] is the local episode id used to launch the player preview.
 */
data class SocialChatEpisode(
    val id: Long,
    val key: EpisodeKey,
    val number: Double,
    val title: String?,
    val durationSeconds: Long,
) : java.io.Serializable

/** Friend bubble shown in the work chat top bar. */
data class SocialChatFriend(
    val mxid: String,
    val name: String,
    val avatarBytes: ByteArray?,
)

/** Context carried by the work-level chat screen. */
data class SocialChatContext(
    val animeId: Long,
    val work: WorkIdentity,
    val title: String,
    val posterUrl: String?,
    val episodes: List<SocialChatEpisode>,
) : java.io.Serializable


