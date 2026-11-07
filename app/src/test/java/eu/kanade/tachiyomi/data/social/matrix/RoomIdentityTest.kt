package eu.kanade.tachiyomi.data.social.matrix

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoomIdentityTest {

    private val workId = WorkId.parse("anilist:154587")!!
    private val episodeKey = EpisodeKey.parse("E003")!!

    @Test
    fun `room topic round trips through the canonical parser`() {
        val key = SocialRoomKey("@alice:example.org", workId)

        assertEquals(key, SocialRoomKey.parseTopic(key.roomTopic()))
    }

    @Test
    fun `room topic parsing rejects anything not produced by roomTopic`() {
        assertNull(SocialRoomKey.parseTopic("Shiro · anilist:154587"))
        assertNull(SocialRoomKey.parseTopic("Shiro · anilist:154587 · fil de"))
        assertNull(SocialRoomKey.parseTopic("Shiro · unknown:1 · fil de @alice:example.org"))
        assertNull(SocialRoomKey.parseTopic("Shiro · anilist:154587 · fil de alice"))
        assertNull(SocialRoomKey.parseTopic("Shiro · anilist:154587 · ami de @alice:example.org"))
        assertNull(SocialRoomKey.parseTopic("Shiro · anilist:154587 · fil de @alice:example.org · extra"))
    }

    @Test
    fun `thread root body keeps the last segment as the episode key`() {
        val body = SocialSchemaKeys.threadRootBody(
            workTitle = "Frieren · Beyond Journey's End",
            workId = workId,
            episodeKey = episodeKey,
        )

        assertEquals("Fil · Frieren · Beyond Journey's End · E003", body)
        assertEquals(episodeKey, SocialSchemaKeys.threadRootEpisodeKey(body))
    }

    @Test
    fun `thread root body parsing is fail closed`() {
        assertNull(SocialSchemaKeys.threadRootEpisodeKey(""))
        assertNull(SocialSchemaKeys.threadRootEpisodeKey("Fil · Frieren"))
        assertNull(SocialSchemaKeys.threadRootEpisodeKey("Message · Frieren · E003"))
        assertNull(SocialSchemaKeys.threadRootEpisodeKey("Fil · Frieren · not-an-episode"))
    }
}
