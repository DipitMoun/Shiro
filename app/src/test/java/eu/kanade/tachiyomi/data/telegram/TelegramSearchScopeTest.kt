package eu.kanade.tachiyomi.data.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TelegramSearchScopeTest {

    @Test
    fun `search only uses selected video and document channels`() {
        val selected = selectedTelegramChatIds(
            videoChats = setOf(1L, 2L),
            docChats = setOf(3L),
            disabledChats = emptySet(),
        )

        assertEquals(setOf(1L, 2L, 3L), selected)
    }

    @Test
    fun `disabled channels are never searched`() {
        val selected = selectedTelegramChatIds(
            videoChats = setOf(1L, 2L),
            docChats = setOf(3L),
            disabledChats = setOf(2L, 3L),
        )

        assertEquals(setOf(1L), selected)
    }

    @Test
    fun `channels simply known are not searched`() {
        // Un titre mémorisé ne vaut pas sélection : la whitelist ne contient
        // que les modes Vidéo/Documents explicitement activés.
        assertEquals(
            emptySet<Long>(),
            selectedTelegramChatIds(emptySet(), emptySet(), emptySet()),
        )
    }
}
