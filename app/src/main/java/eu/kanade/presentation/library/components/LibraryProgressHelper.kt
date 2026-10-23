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
