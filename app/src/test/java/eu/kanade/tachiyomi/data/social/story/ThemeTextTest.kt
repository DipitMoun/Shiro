package eu.kanade.tachiyomi.data.social.story

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThemeTextTest {

    @Test
    fun `an entry reads as title and artist`() {
        assertEquals("Gurenge" to "LiSA", JikanThemeText.parse("1: \"Gurenge\" by LiSA (eps 1-26)"))
    }

    @Test
    fun `the newer numbering with a hash is read too`() {
        assertEquals(
            "Kaikai Kitan" to "Eve",
            JikanThemeText.parse("#1: \"Kaikai Kitan\" by Eve (eps 1-12)"),
        )
    }

    @Test
    fun `a title with its own parentheses and an entry without episodes`() {
        assertEquals(
            "Gurenge (紅蓮華)" to "LiSA",
            JikanThemeText.parse("1: \"Gurenge (紅蓮華)\" by LiSA"),
        )
    }

    @Test
    fun `text that is not an entry is ignored`() {
        assertNull(JikanThemeText.parse("no theme here"))
    }

    @Test
    fun `the slug picks the entry from the right list`() {
        val openings = listOf("1: \"One\" by A (eps 1-5)", "2: \"Two\" by B (eps 6-10)")
        val endings = listOf("1: \"End\" by C (eps 1-10)")

        assertEquals("Two" to "B", JikanThemeText.pick(openings, endings, "OP2"))
        assertEquals("End" to "C", JikanThemeText.pick(openings, endings, "ED1"))
        assertEquals("One" to "A", JikanThemeText.pick(openings, endings, "OP"))
        assertNull(JikanThemeText.pick(openings, endings, "ED3"))
        assertNull(JikanThemeText.pick(openings, endings, null))
    }

    @Test
    fun `the slug travels in the reference`() {
        val ref = ThemeRef(anilistId = 5, themeId = 6, startSeconds = 10, label = "X · OP1", slug = "OP1")

        assertEquals(ref, ThemeRef.decode(ref.encode()))
    }
}
