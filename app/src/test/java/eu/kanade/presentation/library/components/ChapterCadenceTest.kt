package eu.kanade.presentation.library.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class ChapterCadenceTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 27)

    private fun release(number: Int, day: LocalDate) =
        ChapterRelease(number.toDouble(), day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli())

    private fun weekly(lastDay: LocalDate, count: Int = 6, lastNumber: Int = 100) =
        (0 until count).map { release(lastNumber - it, lastDay.minusDays(7L * it)) }

    @Test
    fun `a weekly series is expected a week after its last chapter`() {
        val forecast = ChapterCadence.forecast(weekly(today.minusDays(3)), today, zone)!!
        assertEquals(today.plusDays(4), forecast.day)
        assertEquals(101, forecast.number)
    }

    @Test
    fun `a batch on one day counts as one release`() {
        val batch = listOf(release(98, today.minusDays(10)), release(99, today.minusDays(10))) +
            weekly(today.minusDays(3), count = 4, lastNumber = 100)
        assertNotNull(ChapterCadence.forecast(batch, today, zone))
    }

    @Test
    fun `a little late still points to today`() {
        // Last chapter 9 days ago, rhythm of 7: two days late, within half a period.
        val forecast = ChapterCadence.forecast(weekly(today.minusDays(9)), today, zone)!!
        assertEquals(today, forecast.day)
    }

    @Test
    fun `much later than the rhythm is taken for a pause`() {
        assertNull(ChapterCadence.forecast(weekly(today.minusDays(30)), today, zone))
    }

    @Test
    fun `too few chapters give no guess`() {
        assertNull(ChapterCadence.forecast(weekly(today.minusDays(3), count = 3), today, zone))
    }

    @Test
    fun `an irregular series gives no guess`() {
        val days = listOf(0L, 2L, 15L, 18L, 40L, 41L).map { today.minusDays(60 - it) }
        val irregular = days.mapIndexed { i, d -> release(i + 1, d) }
        assertNull(ChapterCadence.forecast(irregular, today, zone))
    }

    @Test
    fun `chapters without a date are ignored`() {
        val undated = (1..8).map { ChapterRelease(it.toDouble(), 0L) }
        assertNull(ChapterCadence.forecast(undated, today, zone))
    }
}
