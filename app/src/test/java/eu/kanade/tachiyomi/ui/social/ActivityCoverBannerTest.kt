package eu.kanade.tachiyomi.ui.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityCoverBannerTest {

    @Test
    fun `a poster fills the height inside the margins`() {
        val (w, h) = ActivityCoverBanner.posterSize(600, 900, 512, 28, 512)
        assertEquals(456, h)
        assertEquals(304, w)
    }

    @Test
    fun `a wide image is held to the maximum width`() {
        val (w, h) = ActivityCoverBanner.posterSize(1600, 900, 512, 28, 400)
        assertEquals(400, w)
        assertTrue(h < 456)
    }

    @Test
    fun `an empty image gives nothing to draw`() {
        assertEquals(0 to 0, ActivityCoverBanner.posterSize(0, 0, 512, 28, 400))
    }
}
