package eu.kanade.presentation.library.components

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import java.util.concurrent.ConcurrentHashMap

/**
 * Progress of a single entry.
 *
 * [seriesPercent] is the share of the whole series that has been completed.
 * [episodePercent] is the progress inside the episode currently being watched, or null when there is
 * no episode in progress (nothing started, or everything watched).
 */
@Immutable
data class EntryProgress(
    val seriesPercent: Int,
    val episodePercent: Int? = null,
) {
    companion object {
        val Empty = EntryProgress(seriesPercent = 0)
    }
}

private data class ProgressCacheKey(
    val lastSeen: Long,
    val seenCount: Long,
)

private val animeProgressCache = ConcurrentHashMap<Long, Pair<ProgressCacheKey, EntryProgress>>()

fun AnimeLibraryItem.progressPercent(): Int {
    val total = libraryAnime.totalCount
    val seen = libraryAnime.seenCount
    if (total <= 0L) return 0
    return ((seen.toFloat() / total) * 100).toInt().coerceIn(0, 100)
}

fun MangaLibraryItem.progressPercent(): Int {
    val total = libraryManga.totalChapters
    val read = libraryManga.readCount
    if (total <= 0L) return 0
    return ((read.toFloat() / total) * 100).toInt().coerceIn(0, 100)
}

/**
 * Loads the series and current episode progress of [item].
 *
 * Results are cached per anime and invalidated as soon as [AnimeLibraryItem.libraryAnime] changes,
 * so scrolling the grid does not hit the database again for an unchanged entry.
 */
suspend fun loadAnimeProgress(
    item: AnimeLibraryItem,
    getEpisodes: GetEpisodesByAnimeId,
): EntryProgress {
    val libraryAnime = item.libraryAnime
    val animeId = libraryAnime.anime.id
    val cacheKey = ProgressCacheKey(lastSeen = libraryAnime.lastSeen, seenCount = libraryAnime.seenCount)

    animeProgressCache[animeId]?.let { (cachedKey, cachedProgress) ->
        if (cachedKey == cacheKey) return cachedProgress
    }

    val currentEpisode = getEpisodes.await(animeId)
        .filter { !it.seen && it.lastSecondSeen > 0L && it.totalSeconds > 0L }
        .maxByOrNull { it.episodeNumber }

    val progress = EntryProgress(
        seriesPercent = item.progressPercent(),
        episodePercent = currentEpisode?.let {
            ((it.lastSecondSeen.toFloat() / it.totalSeconds) * 100).toInt().coerceIn(0, 100)
        },
    )

    animeProgressCache[animeId] = cacheKey to progress
    return progress
}

suspend fun computeAnimeStats(
    items: List<AnimeLibraryItem>,
    getEpisodes: GetEpisodesByAnimeId,
): String {
    if (items.isEmpty()) return "0 titres - 0/0 ep. - 0m"

    var totalMsWatched = 0L
    items.forEach { item ->
        getEpisodes.await(item.libraryAnime.anime.id).forEach { episode ->
            totalMsWatched += if (episode.seen) {
                episode.totalSeconds // Dans Aniyomi, c'est deja en ms
            } else {
                episode.lastSecondSeen // Deja en ms
            }
        }
    }

    val totalSeconds = totalMsWatched / 1000
    val watchedHours = totalSeconds / 3600
    val watchedMinutes = (totalSeconds % 3600) / 60

    val timeStr = if (watchedHours > 0) {
        "${watchedHours}h${watchedMinutes}m"
    } else {
        "${watchedMinutes}m"
    }

    val titres = items.size
    val seen = items.sumOf { it.libraryAnime.seenCount }
    val total = items.sumOf { it.libraryAnime.totalCount }
    return "$titres titres - $seen/$total ep. - $timeStr vues"
}

fun List<MangaLibraryItem>.mangaStatsText(): String {
    val titres = size
    val read = sumOf { it.libraryManga.readCount }
    val total = sumOf { it.libraryManga.totalChapters }
    return "$titres titres - $read / $total chapitres lus"
}

/**
 * Resolves the model to give to Coil for a collection banner: the local file when it exists,
 * otherwise the remote URL, otherwise null.
 */
fun collectionBannerModel(bannerPath: String?, bannerUrl: String?): Any? {
    return when {
        !bannerPath.isNullOrBlank() && java.io.File(bannerPath).exists() -> java.io.File(bannerPath)
        !bannerUrl.isNullOrBlank() -> bannerUrl
        else -> null
    }
}
