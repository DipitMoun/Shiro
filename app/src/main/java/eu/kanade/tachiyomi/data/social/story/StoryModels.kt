package eu.kanade.tachiyomi.data.social.story

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a story shows. A photo or a video is a media message in the author's story room; a music
 * story is only a reference to an AnimeThemes theme (no file, no quota): the friend's app resolves
 * it when the story is played and streams it, and nothing of AnimeThemes is copied or re-hosted.
 */
sealed interface StoryContent {
    data class Media(
        val isVideo: Boolean,
        /** Opaque handle the media loader understands (a Matrix media source). */
        val source: Any,
        val mimeType: String?,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val sizeBytes: Long?,
    ) : StoryContent

    data class Music(val theme: ThemeRef) : StoryContent
}

/** A theme of an anime, as AnimeThemes knows it, and where to start playing it. */
@Serializable
data class ThemeRef(
    /** AniList id of the anime: the identifier the app already holds for its library. */
    @SerialName("a") val anilistId: Long,
    /** AnimeThemes id of the theme (opening or ending). */
    @SerialName("th") val themeId: Long,
    /** Where the extract starts, in seconds. */
    @SerialName("s") val startSeconds: Int = 0,
    /** Label for the card, so something readable shows before (or without) the resolution. */
    @SerialName("t") val label: String? = null,
    /** "OP1", "ED2"…: lets a backup source find the title and artist when AnimeThemes is down. */
    @SerialName("sl") val slug: String? = null,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        /** Custom message type of a music story. */
        const val MSGTYPE = "org.shiro.story.theme"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(body: String): ThemeRef? =
            runCatching { JSON.decodeFromString(serializer(), body) }.getOrNull()
                ?.takeIf { it.anilistId > 0 && it.themeId > 0 && it.startSeconds >= 0 }
    }
}

data class StoryItem(
    val eventId: String,
    val authorMxid: String,
    val postedAtMillis: Long,
    val caption: String?,
    val content: StoryContent,
) {
    val expiresAtMillis: Long get() = StoryExpiry.expiresAt(postedAtMillis)
}

/** The stories of one person, oldest first, as Instagram shows them behind one avatar. */
data class StoryReel(
    val authorMxid: String,
    val items: List<StoryItem>,
    /** True when something in the reel is newer than what the user last watched. */
    val hasUnseen: Boolean,
)

object StoryReels {

    /**
     * Groups stories by author, keeps only the active ones, and puts the ones with something new
     * first (then the most recent). [seenUpTo] maps an author to the timestamp of the last story
     * of theirs the user watched.
     */
    fun build(
        items: List<StoryItem>,
        now: Long,
        seenUpTo: Map<String, Long>,
        selfMxid: String,
    ): List<StoryReel> =
        items
            .filter { StoryExpiry.isActive(it.postedAtMillis, now) }
            .groupBy { it.authorMxid }
            .map { (author, stories) ->
                val sorted = stories.sortedBy { it.postedAtMillis }
                StoryReel(
                    authorMxid = author,
                    items = sorted,
                    hasUnseen = author != selfMxid && sorted.any { it.postedAtMillis > (seenUpTo[author] ?: 0L) },
                )
            }
            .sortedWith(
                compareByDescending<StoryReel> { it.authorMxid == selfMxid }
                    .thenByDescending { it.hasUnseen }
                    .thenByDescending { it.items.last().postedAtMillis },
            )
}
