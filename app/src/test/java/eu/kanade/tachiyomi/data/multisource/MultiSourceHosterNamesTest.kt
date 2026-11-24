package eu.kanade.tachiyomi.data.multisource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MultiSourceHosterNamesTest {

    @Test
    fun `compose then split returns the original parts`() {
        val name = MultiSourceHosterNames.compose("Voiranime", "Voe 720p")

        assertEquals("Voiranime · Voe 720p", name)
        assertEquals("Voiranime" to "Voe 720p", MultiSourceHosterNames.split(name))
    }

    @Test
    fun `a name without a source prefix has no source`() {
        val (source, hoster) = MultiSourceHosterNames.split("Streamtape")

        assertNull(source)
        assertEquals("Streamtape", hoster)
    }

    @Test
    fun `only the first separator splits so hoster names may contain it`() {
        assertEquals(
            "Anime-Sama" to "Sibnet · HD",
            MultiSourceHosterNames.split(MultiSourceHosterNames.compose("Anime-Sama", "Sibnet · HD")),
        )
    }
}
