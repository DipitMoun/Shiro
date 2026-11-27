package eu.kanade.presentation.library.components

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A chapter that came out: its number and the day the source says it was published. */
data class ChapterRelease(val number: Double, val uploadedAtMillis: Long)

/** The next chapter of a series, as worked out from the rhythm of the previous ones. */
data class ChapterForecast(val number: Int, val day: LocalDate)

/**
 * No source announces the date of a chapter, so it is guessed: a series that came out every seven
 * days, with a few days of slack, is expected seven days after its last chapter. The guess is
 * only made when the rhythm is clear (several chapters, similar gaps) and the series is not
 * late by more than half a period, which is taken for a pause.
 */
object ChapterCadence {

    private const val MIN_DAYS = 4
    private const val MAX_GAPS = 7
    private const val MAX_PERIOD_DAYS = 45

    fun forecast(releases: List<ChapterRelease>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ChapterForecast? {
        val dated = releases.filter { it.uploadedAtMillis > 0L }
        // Several chapters on one day (a batch) are one release.
        val days = dated
            .map { Instant.ofEpochMilli(it.uploadedAtMillis).atZone(zone).toLocalDate() }
            .distinct()
            .sorted()
            .takeLast(MAX_GAPS + 1)
        if (days.size < MIN_DAYS) return null

        val gaps = days.zipWithNext { a, b -> (b.toEpochDay() - a.toEpochDay()).toInt() }
        val median = gaps.sorted()[gaps.size / 2]
        if (median < 1 || median > MAX_PERIOD_DAYS) return null

        // Regular enough: most gaps are close to the usual one.
        val slack = maxOf(1, median / 4)
        val regular = gaps.count { kotlin.math.abs(it - median) <= slack }
        if (regular * 100 < gaps.size * 60) return null

        val next = days.last().plusDays(median.toLong())
        val late = today.toEpochDay() - next.toEpochDay()
        val day = when {
            late <= 0 -> next
            late <= maxOf(1, median / 2) -> today
            else -> return null
        }
        val highest = dated.maxOf { it.number }
        val number = if (highest > 0.0) highest.toInt() + 1 else dated.size + 1
        return ChapterForecast(number, day)
    }
}

/**
 * Chapters of the library that are expected in the next days, nearest first. Only series that are
 * still running, and only when the rhythm of their chapters allows a guess.
 */
class MangaUpcomingLoader(
    private val getChapters: GetChaptersByMangaId = Injekt.get(),
) {

    suspend fun load(
        library: List<Manga>,
        withinDays: Int = 7,
        limit: Int = 8,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<Manga, ChapterForecast>> {
        val today = LocalDate.now(zone)
        val last = today.plusDays(withinDays.toLong())
        return library
            .filter { it.status == SManga.ONGOING.toLong() || it.status == SManga.UNKNOWN.toLong() }
            .sortedByDescending { it.lastUpdate }
            .take(MAX_SERIES)
            .mapNotNull { manga ->
                val releases = runCatching { getChapters.await(manga.id) }.getOrDefault(emptyList())
                    .map { ChapterRelease(it.chapterNumber, it.dateUpload) }
                ChapterCadence.forecast(releases, today, zone)?.let { manga to it }
            }
            .filter { (_, forecast) -> forecast.day in today..last }
            .sortedBy { it.second.day }
            .take(limit)
    }

    private companion object {
        const val MAX_SERIES = 150
    }
}
