package eu.kanade.tachiyomi.data.multisource

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.media.EpisodeMediaValidator
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Names of the hosters exposed by a multi-source work: "Source · Hoster". */
object MultiSourceHosterNames {
    private const val SEPARATOR = " · "

    fun compose(sourceName: String, hosterName: String) = "$sourceName$SEPARATOR$hosterName"

    /** @return (source name or null, hoster name) */
    fun split(name: String): Pair<String?, String> {
        val index = name.indexOf(SEPARATOR)
        return if (index < 0) null to name else name.substring(0, index) to name.substring(index + SEPARATOR.length)
    }
}

/** What the user picked for a work, reused by grouped downloads of that work. */
@Serializable
data class MultiSourceDownloadChoice(
    val sourceName: String,
    val hosterName: String,
    val resolution: Int? = null,
    val videoTitle: String = "",
)

class MultiSourceDownloadChoiceStore(context: Context) {
    private val prefs = context.getSharedPreferences("multi_source_download_choice", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(workUrl: String, choice: MultiSourceDownloadChoice) {
        prefs.edit().putString(workUrl, json.encodeToString(choice)).apply()
    }

    fun get(workUrl: String): MultiSourceDownloadChoice? {
        val raw = prefs.getString(workUrl, null) ?: return null
        return runCatching { json.decodeFromString<MultiSourceDownloadChoice>(raw) }.getOrNull()
    }

    fun clear(workUrl: String) {
        prefs.edit().remove(workUrl).apply()
    }

    /**
     * Picks, among [hosters], the video matching the work's stored choice: same source first,
     * then same hoster, then the closest resolution. Null when the source no longer offers the
     * episode or nothing playable matches, so the caller falls back to the automatic choice.
     */
    suspend fun pickVideo(workUrl: String, source: AnimeSource, hosters: List<Hoster>): Video? {
        val choice = get(workUrl) ?: return null
        val candidates = hosters
            .filter { MultiSourceHosterNames.split(it.hosterName).first == choice.sourceName }
            .sortedByDescending { MultiSourceHosterNames.split(it.hosterName).second == choice.hosterName }

        for (hoster in candidates) {
            val state = EpisodeLoader.loadHosterVideos(source, hoster) as? HosterState.Ready ?: continue
            val ranked = state.videoList.filter { it.videoUrl.isNotBlank() || !it.initialized }
                .sortedBy { video -> resolutionDistance(video.resolution, choice) }
            for (video in ranked) {
                val resolved = HosterLoader.getResolvedVideo(source, video) ?: continue
                if (EpisodeMediaValidator.isAcceptableVideoUrl(resolved.videoUrl)) return resolved
            }
        }
        return null
    }

    private fun resolutionDistance(resolution: Int?, choice: MultiSourceDownloadChoice): Int {
        val wanted = choice.resolution ?: return 0
        return if (resolution == null) Int.MAX_VALUE else abs(resolution - wanted)
    }
}
