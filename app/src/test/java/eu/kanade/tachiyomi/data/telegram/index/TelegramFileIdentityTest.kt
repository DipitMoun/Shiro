package eu.kanade.tachiyomi.data.telegram.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TelegramFileIdentityTest {

    @Test
    fun identicalFilesAreDeduplicatedEvenWithDifferentTelegramIds() {
        val first = file(fileId = 1, messageId = 10, size = 100, episode = 1)
        val forwarded = file(fileId = 99, messageId = 20, size = 100, episode = 1)

        assertEquals(first.identityKey(), forwarded.identityKey())
    }

    @Test
    fun differentFileSizesAreKept() {
        val small = file(fileId = 1, messageId = 10, size = 100, episode = 1)
        val large = file(fileId = 2, messageId = 11, size = 200, episode = 1)

        assertNotEquals(small.identityKey(), large.identityKey())
    }

    @Test
    fun episodeKeysCollapseMultipleVersionsOfTheSameEpisode() {
        val small = file(fileId = 1, messageId = 10, size = 100, episode = 1)
        val large = file(fileId = 2, messageId = 11, size = 200, episode = 1)

        assertEquals("e:1:1", small.episodeKey())
        assertEquals(small.episodeKey(), large.episodeKey())
    }

    private fun file(fileId: Int, messageId: Long, size: Long, episode: Int): TelegramIndexedFile {
        return TelegramIndexedFile(
            fileId = fileId,
            messageId = messageId,
            chatId = -100,
            fileName = "Episode $episode.mkv",
            sizeBytes = size,
            date = 0L,
            mediaKind = TelegramMediaKind.VIDEO,
            title = "Test",
            season = 1,
            episode = episode,
            workId = "test:0",
        )
    }
}
