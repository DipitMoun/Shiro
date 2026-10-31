package eu.kanade.tachiyomi.ui.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialUiStateTest {

    private val context = SocialMomentContext(
        workTitle = "Frieren",
        episodeLabel = "S1 E12",
        positionMs = 300_000L,
        durationMs = 1_440_000L,
    )

    private fun state() = SocialUiState(context)

    @Test
    fun `selecting an author opens that author's thread`() {
        val state = state()

        state.selectAuthor("david")

        assertEquals("thread-david", state.selectedThreadId)
        assertEquals("david", state.selectedThread?.authorId)
        assertEquals("David", state.selectedAuthor?.name)
        assertEquals(0, state.unread["david"])
    }

    @Test
    fun `new chat selects the personal empty thread`() {
        val state = state()

        state.newChat()

        assertEquals("thread-me", state.selectedThreadId)
        assertEquals(SocialDemoData.ME, state.selectedAuthor?.id)
        assertTrue(state.selectedThread?.messages?.isEmpty() == true)
    }

    @Test
    fun `reply trims text and attaches the current anchor`() {
        val state = state()
        state.selectAuthor("kevin")
        state.selectAnchor(123_456L)

        state.send("  bonjour  ", publish = false)

        val message = state.selectedThread?.messages?.last()
        assertEquals("bonjour", message?.text)
        assertEquals(123_456L, message?.positionMs)
        assertEquals(SocialDemoData.ME, message?.authorId)
    }

    @Test
    fun `publish targets the personal thread`() {
        val state = state()
        state.selectAuthor("allan")

        state.send("mon moment", publish = true)

        assertEquals("thread-me", state.selectedThreadId)
        assertEquals("mon moment", state.selectedThread?.messages?.last()?.text)
    }

    @Test
    fun `reaction increments only the target message`() {
        val state = state()
        val message = state.threads
            .first { it.authorId == "kevin" }
            .messages
            .first { it.id == "kevin-2" }
        val before = message.reactions["😱"] ?: 0

        state.react("kevin-2", "😱")

        val after = state.threads
            .flatMap { it.messages }
            .first { it.id == "kevin-2" }
            .reactions["😱"]
        assertEquals(before + 1, after)
    }

    @Test
    fun `empty demo keeps only the personal message history`() {
        val state = state()

        state.reload(DemoMode.Empty)

        assertTrue(state.threads.filter { it.authorId != SocialDemoData.ME }.all { it.messages.isEmpty() })
        assertTrue(state.threads.firstOrNull { it.authorId == SocialDemoData.ME } != null)
    }

    @Test
    fun `loading demo clears content and selection`() {
        val state = state()

        state.reload(DemoMode.Loading)

        assertTrue(state.threads.isEmpty())
        assertNull(state.selectedThreadId)
        assertNull(state.selectedThread)
    }
}
