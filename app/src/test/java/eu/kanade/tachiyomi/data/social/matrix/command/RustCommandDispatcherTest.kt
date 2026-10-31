package eu.kanade.tachiyomi.data.social.matrix.command

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.room.InMemoryRoomBindingStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RustCommandDispatcherTest {

    private val thread = ThreadRef(
        authorMxid = "@alice:example.org",
        workId = WorkId.parse("anilist:154587")!!,
        episodeKey = EpisodeKey.parse("S01E003")!!,
    )

    @Test
    fun `execute returns Auth failure when no session`() = runBlocking {
        val dispatcher = RustCommandDispatcher({ null }, InMemoryRoomBindingStore())
        val result = dispatcher.execute(SocialCommand.React(thread, "\$e1", "fire"))
        assertTrue(result is SocialResult.Failure)
        val error = (result as SocialResult.Failure).error
        assertTrue(error is SocialError.Auth)
        assertEquals(AuthFailureReason.NOT_ALLOWED, (error as SocialError.Auth).reason)
    }

    @Test
    fun `all command types fail without session`() = runBlocking {
        val dispatcher = RustCommandDispatcher({ null }, InMemoryRoomBindingStore())
        val commands = listOf(
            SocialCommand.ReplyInAuthorThread(thread, "hi"),
            SocialCommand.React(thread, "\$e1", "fire"),
            SocialCommand.Edit(thread, "\$e1", "new"),
            SocialCommand.Redact(thread, "\$e1", "no spoiler"),
        )
        commands.forEach { cmd ->
            val result = dispatcher.execute(cmd)
            assertTrue(result is SocialResult.Failure, "expected Failure for $cmd")
        }
    }
}