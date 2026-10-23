package eu.kanade.presentation.library.components

import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId

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

suspend fun computeAnimeStats(
    items: List<AnimeLibraryItem>,
    getEpisodes: GetEpisodesByAnimeId,
): String {
    if (items.isEmpty()) return ""

    var totalSecondsWatched = 0L
    items.forEach { item ->
        getEpisodes.await(item.libraryAnime.anime.id).forEach { episode ->
            totalSecondsWatched += if (episode.seen) episode.totalSeconds else episode.lastSecondSeen
        }
    }

    val titres = items.size
    val seen = items.sumOf { it.libraryAnime.seenCount }
    val total = items.sumOf { it.libraryAnime.totalCount }
    val watchedHours = totalSecondsWatched / 3600
    return "$titres titres · $seen/$total ép. · ${watchedHours}h vues"
}

fun List<MangaLibraryItem>.mangaStatsText(): String {
    val titres = size
    val read = sumOf { it.libraryManga.readCount }
    val total = sumOf { it.libraryManga.totalChapters }
    return "$titres titres · $read / $total chapitres lus"
}
