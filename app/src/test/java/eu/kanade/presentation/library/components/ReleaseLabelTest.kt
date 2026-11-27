package eu.kanade.presentation.library.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReleaseLabelTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 11, 27, 14, 0, 0, 0, zone)
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toEpochSecond()

    @Test
    fun `later today`() = assertEquals("Ép. 11 · Auj. 19:30", releaseLabel(at(27, 19, 30), "Ép.", 11, false, now))

    @Test
    fun `tomorrow`() = assertEquals("Ép. 11 · Demain 19:30", releaseLabel(at(28, 19, 30), "Ép.", 11, false, now))

    @Test
    fun `within the week it names the day`() = assertEquals("Ép. 11 · Lun 08:00", releaseLabel(at(30, 8), "Ép.", 11, false, now))

    @Test
    fun `within the hour it counts minutes`() = assertEquals("Ép. 11 · dans 30 min", releaseLabel(at(27, 14, 30), "Ép.", 11, false, now))

    @Test
    fun `an estimated chapter has no hour and says around`() {
        assertEquals("Ch. 313 · vers lun", releaseLabel(at(30, 12), "Ch.", 313, true, now))
        assertEquals("Ch. 313 · vers demain", releaseLabel(at(28, 12), "Ch.", 313, true, now))
        assertEquals("Ch. 313 · vers auj.", releaseLabel(at(27, 12), "Ch.", 313, true, now))
    }

    @Test
    fun `the ring fills as the day approaches`() {
        val nowSeconds = now.toEpochSecond()
        val far = ringProgress(nowSeconds + 7 * 86_400, nowSeconds)
        val near = ringProgress(nowSeconds + 3_600, nowSeconds)
        assertTrue(far < 0.1f)
        assertTrue(near > 0.9f)
        assertTrue(ringProgress(nowSeconds - 10, nowSeconds) <= 1f)
    }
}
