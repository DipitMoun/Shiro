package eu.kanade.tachiyomi.data.social.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class StatusRulesTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `text is trimmed, flattened and capped`() {
        assertEquals("a b c", StatusRules.clean("  a \n b\t c  "))
        assertEquals(StatusRules.MAX_LENGTH, StatusRules.clean("x".repeat(200)).length)
        assertEquals("", StatusRules.clean(null))
    }

    @Test
    fun `a status lasts a day`() {
        assertTrue(StatusRules.isActive(now - TimeUnit.HOURS.toMillis(23), now))
        assertFalse(StatusRules.isActive(now - TimeUnit.HOURS.toMillis(25), now))
        // A clock that is ahead of ours must not make it last forever.
        assertFalse(StatusRules.isActive(now + TimeUnit.HOURS.toMillis(1), now))
    }

    @Test
    fun `stored status is read back when active`() {
        assertEquals("en plein marathon", StatusRules.parse("""{"text":"en plein marathon","at":${now - 1000}}""", now))
    }

    @Test
    fun `blank, expired or broken status reads as none`() {
        assertNull(StatusRules.parse("""{"text":"","at":${now - 1000}}""", now))
        assertNull(StatusRules.parse("""{"text":"vieux","at":${now - TimeUnit.HOURS.toMillis(30)}}""", now))
        assertNull(StatusRules.parse("""{"text":"sans date"}""", now))
        assertNull(StatusRules.parse("pas du json", now))
    }
}
