package eu.kanade.presentation.library.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CountdownLabelTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 11, 26, 10, 0, 0, 0, zone)

    private fun label(at: ZonedDateTime) = countdownLabel(at.toEpochSecond(), now)

    @Test
    fun `under an hour it counts the minutes`() {
        assertEquals("30 min", label(now.plusMinutes(30)))
        assertEquals("1 min", label(now.plusSeconds(10)))
    }

    @Test
    fun `later today it shows the hour`() {
        assertEquals("19 h", label(now.withHour(19)))
        assertEquals("19:30", label(now.withHour(19).withMinute(30)))
    }

    @Test
    fun `tomorrow and the days after`() {
        assertEquals("Demain", label(now.plusDays(1).withHour(9)))
        assertEquals("J‑3", label(now.plusDays(3).withHour(9)))
    }

    @Test
    fun `an episode already out says so`() {
        assertEquals("Sorti", label(now.minusMinutes(5)))
    }
}
