package eu.kanade.tachiyomi.data.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReleaseReminderRulesTest {

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
    private fun due(airing: ZonedDateTime, now: ZonedDateTime) = ReleaseReminderRules.due(airing.toEpochSecond(), now)

    @Test
    fun `the evening before, from 18h`() {
        assertEquals(ReminderKind.EVE, due(at(28, 20, 30), at(27, 18, 5)))
        assertEquals(ReminderKind.EVE, due(at(28, 0, 30), at(27, 22)))
    }

    @Test
    fun `nothing before 18h the day before`() {
        assertNull(due(at(28, 20, 30), at(27, 17, 59)))
        assertNull(due(at(28, 20, 30), at(27, 9)))
    }

    @Test
    fun `shortly before it airs`() {
        assertEquals(ReminderKind.DAY, due(at(28, 20, 30), at(28, 19, 5)))
        assertEquals(ReminderKind.DAY, due(at(28, 20, 30), at(28, 20, 29)))
    }

    @Test
    fun `not when it is still far or already out`() {
        assertNull(due(at(28, 20, 30), at(28, 12)))
        assertNull(due(at(28, 20, 30), at(28, 20, 31)))
    }

    @Test
    fun `the day reminder wins when both would apply`() {
        // Airs at 00:30 tomorrow, it is 23:30: within 90 minutes, so it is the "now" reminder.
        assertEquals(ReminderKind.DAY, due(at(28, 0, 30), at(27, 23, 30)))
    }

    @Test
    fun `two days away is too early`() {
        assertNull(due(at(29, 20), at(27, 21)))
    }
}
