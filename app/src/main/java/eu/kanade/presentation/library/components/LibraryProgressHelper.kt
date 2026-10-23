package eu.kanade.presentation.library.components

import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem

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

fun List<AnimeLibraryItem>.statsText(): String {
    val titres = size
    val seen = sumOf { it.libraryAnime.seenCount }
    val total = sumOf { it.libraryAnime.totalCount }
    return "$titres titres · $seen / $total épisodes vus"
}

fun List<MangaLibraryItem>.mangaStatsText(): String {
    val titres = size
    val read = sumOf { it.libraryManga.readCount }
    val total = sumOf { it.libraryManga.totalChapters }
    return "$titres titres · $read / $total chapitres lus"
}
