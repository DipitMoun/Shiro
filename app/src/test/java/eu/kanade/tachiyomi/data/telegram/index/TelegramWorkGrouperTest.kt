package eu.kanade.tachiyomi.data.telegram.index

import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkGrouper.Input
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramWorkGrouperTest {

    @Test
    fun `hell paradise variants merge into a single season`() {
        val files = listOf(
            input(1, 2, 12),
            input(2, 2, 12),
            input(3, 2, 11),
            input(4, 2, 10),
            input(5, 2, 9),
        )

        val groups = TelegramWorkGrouper.group(files)

        assertEquals(5, groups.size)
        assertEquals(1, groups.map { it.title to it.season }.distinct().size)
        assertTrue(groups.all { it.season == 2 && it.title == "HELL'S PARADISE" })
    }

    @Test
    fun `absolute numbering stays out of the explicit season`() {
        val seasonTwo = (1..5).map { number ->
            Input(fileId = number, title = "Black Clover", season = 2, number = number)
        }
        val absolute = (166..170).mapIndexed { index, number ->
            Input(fileId = 100 + index, title = "Black Clover", season = null, number = number)
        }

        val groups = TelegramWorkGrouper.group(seasonTwo + absolute)

        assertEquals(5, groups.count { it.season == 2 })
        assertEquals(5, groups.count { it.season == null })
    }

    @Test
    fun `missing season joins the only explicit season`() {
        val files = listOf(
            Input(1, "Tokyo Revengers", season = 2, number = 1),
            Input(2, "Tokyo Revengers", season = 2, number = 2),
            Input(3, "Tokyo Revengers", season = null, number = 3),
        )

        val groups = TelegramWorkGrouper.group(files)

        assertTrue(groups.all { it.season == 2 })
    }

    @Test
    fun `documents ignore seasons and group by title`() {
        val files = listOf(
            Input(1, "Blue Lock", season = 2, number = 360, isDocument = true),
            Input(2, "Blue Lock", season = null, number = 364, isDocument = true),
        )

        val groups = TelegramWorkGrouper.group(files)

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.season == null && it.title == "Blue Lock" })
    }

    @Test
    fun `near identical long titles merge`() {
        val files = listOf(
            Input(1, "JoJo's Bizarre Adventure Part 7 Steel Ball Run", season = 1, number = 1),
            Input(2, "JoJo's Bizarre Adventure Part 7 Steel Ball Run Edition", season = 1, number = 2),
        )

        val groups = TelegramWorkGrouper.group(files)

        assertEquals(1, groups.map { it.title }.distinct().size)
        assertEquals("JoJo's Bizarre Adventure Part 7 Steel Ball Run", groups.first().title)
    }

    @Test
    fun `distinct series and distinct seasons stay apart`() {
        val files = listOf(
            Input(1, "Naruto", season = 1, number = 1),
            Input(2, "Naruto Shippuden", season = 1, number = 1),
            Input(3, "Naruto", season = 2, number = 1),
        )

        val groups = TelegramWorkGrouper.group(files)

        assertEquals(2, groups.map { it.title }.distinct().size)
        assertEquals(
            setOf(1, 2),
            groups.filter { it.title == "Naruto" }.map { it.season }.toSet(),
        )
        assertEquals(1, groups.count { it.title == "Naruto Shippuden" })
    }

    private fun input(fileId: Int, season: Int?, number: Int): Input =
        Input(fileId, "HELL'S PARADISE", season, number)
}
