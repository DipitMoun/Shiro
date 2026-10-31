package eu.kanade.tachiyomi.data.telegram.saved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TelegramSavedWorkTest {

    @Test
    fun `episode count distinguishes seasons`() {
        val files = (1..13).map { file(season = null, episode = it) } +
            (1..12).map { file(season = 2, episode = it) }

        val work = TelegramSavedWork(
            id = "id",
            title = "Hell's Paradise",
            kind = TelegramSavedKind.VIDEO,
            files = files,
        )

        assertEquals(25, work.episodeCount)
    }

    @Test
    fun `duplicate variants do not inflate the episode count`() {
        val files = listOf(
            file(season = 2, episode = 1, language = "VF"),
            file(season = 2, episode = 1, language = "VOSTFR"),
            file(season = 2, episode = 2, language = "VF"),
        )

        val work = TelegramSavedWork(
            id = "id",
            title = "Test",
            kind = TelegramSavedKind.VIDEO,
            files = files,
        )

        assertEquals(2, work.episodeCount)
    }

    private fun file(
        season: Int?,
        episode: Int,
        language: String = "VF",
    ): TelegramSavedFile = TelegramSavedFile(
        chatId = -1L,
        messageId = episode.toLong(),
        fileId = episode,
        fileName = "test_$episode.mp4",
        sizeBytes = 100L,
        season = season,
        episode = episode,
        language = language,
    )
}
