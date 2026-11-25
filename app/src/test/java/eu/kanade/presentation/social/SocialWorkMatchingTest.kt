package eu.kanade.presentation.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SocialWorkMatchingTest {

    private val results = listOf(
        "Le retour",
        "Le retour du clan Hwasan",
        "Retour à la vie",
    )

    @Test
    fun `the exact title wins over a shorter one it contains`() {
        val best = SocialWorkMatching.matchTitle("Le retour du clan Hwasan", results) { it }

        assertEquals("Le retour du clan Hwasan", best)
    }

    @Test
    fun `an exact title wins whatever the order`() {
        val best = SocialWorkMatching.matchTitle("Le retour", results.reversed()) { it }

        assertEquals("Le retour", best)
    }

    @Test
    fun `accents, case and punctuation do not matter`() {
        assertEquals(
            "Youjo Senki 2",
            SocialWorkMatching.matchTitle("youjo  senki-2", listOf("Autre chose", "Youjo Senki 2")) { it },
        )
    }

    @Test
    fun `any of the names of a candidate can match`() {
        val works = listOf(
            listOf("Complètement autre"),
            listOf("Omniscient Reader's Viewpoint", "Lecteur omniscient"),
        )

        assertEquals(works[1], SocialWorkMatching.bestMatch("Lecteur omniscient", works) { it })
    }

    @Test
    fun `a shorter title that is only part of the name is another work`() {
        val library = listOf("Le Retour", "Solo Leveling")

        assertNull(SocialWorkMatching.matchTitle("Le retour du clan Hwasan", library) { it })
    }

    @Test
    fun `a tag after the title does not make it another work`() {
        assertEquals(
            "Youjo Senki 2",
            SocialWorkMatching.matchTitle("Youjo Senki 2 (VOSTFR)", listOf("Youjo Senki 2")) { it },
        )
    }

    @Test
    fun `a synonym of the catalogue entry finds it`() {
        val works = listOf(
            listOf("Return of the Blossoming Blade", "Return of The Mount Hua Sect", "Le retour du clan Hwasan"),
            listOf("Le Retour"),
        )

        assertEquals(works[0], SocialWorkMatching.bestMatch("Le retour du clan Hwasan", works) { it })
    }

    @Test
    fun `nothing close means no match`() {
        assertNull(SocialWorkMatching.matchTitle("Haibane Renmei", listOf("Naruto", "One Piece")) { it })
    }
}
