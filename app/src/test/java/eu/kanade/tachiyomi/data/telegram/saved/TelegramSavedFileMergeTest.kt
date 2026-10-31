package eu.kanade.tachiyomi.data.telegram.saved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TelegramSavedFileMergeTest {

    @Test
    fun `adding files keeps order and ignores duplicates`() {
        val existing = listOf(file(messageId = 2, episode = 2))

        val merged = mergeSavedFiles(
            existing,
            listOf(
                file(messageId = 1, episode = 1),
                file(messageId = 2, episode = 2, variant = "VF"),
            ),
        )

        assertEquals(listOf(1L, 2L), merged.map { it.messageId })
        assertEquals(2, merged.size)
    }

    @Test
    fun `removing files never empties a work by itself`() {
        val files = listOf(
            file(messageId = 1, episode = 1),
            file(messageId = 2, episode = 2),
        )

        val remaining = removeSavedFiles(files, setOf(files.first().stableKey()))

        assertEquals(listOf(2L), remaining.map { it.messageId })
        // Le retrait du dernier fichier est refusé en amont par le modèle :
        // cette fonction reste pure et ne décide pas de supprimer l'œuvre.
        assertEquals(emptyList<TelegramSavedFile>(), removeSavedFiles(files, files.map { it.stableKey() }.toSet()))
    }

    @Test
    fun `stable key is scoped by channel`() {
        val a = file(messageId = 7, episode = 1, chatId = 10L)
        val b = file(messageId = 7, episode = 1, chatId = 20L)

        assertEquals("10:7", a.stableKey())
        assertEquals("20:7", b.stableKey())
    }

    private fun file(
        messageId: Long,
        episode: Int,
        chatId: Long = 1L,
        variant: String? = null,
    ): TelegramSavedFile = TelegramSavedFile(
        chatId = chatId,
        messageId = messageId,
        fileId = messageId.toInt(),
        fileName = "episode_$episode.mp4",
        sizeBytes = 1024L,
        season = 1,
        episode = episode,
        variant = variant,
    )
}
