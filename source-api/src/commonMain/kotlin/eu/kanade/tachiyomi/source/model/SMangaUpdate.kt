package eu.kanade.tachiyomi.source.model

/**
 * Result of [eu.kanade.tachiyomi.source.MangaSource.getMangaUpdate]: the manga details and the
 * chapter list, fetched together (extensions-lib / tachiyomix 1.6).
 */
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
