package eu.kanade.tachiyomi.data.social.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ActivityFeedTest {

    private val me = "@me:matrix.org"
    private val friend = "@ami:matrix.org"

    private fun item(
        id: String,
        actor: String,
        title: String,
        at: Long,
        kind: SocialActivityKind = SocialActivityKind.ANIME_ADDED,
    ) = SocialActivityItem(id, actor, kind, title, null, at)

    @Test
    fun `newest first and the same event once`() {
        val merged = ActivityFeedRules.merge(
            existing = listOf(item("a", friend, "Frieren", 10)),
            incoming = listOf(item("a", friend, "Frieren", 10), item("b", friend, "Dandadan", 20)),
            selfMxid = me,
            max = 50,
        )
        assertEquals(listOf("b", "a"), merged.map { it.eventId })
    }

    @Test
    fun `my own addition is one entry even when read back from my room`() {
        val merged = ActivityFeedRules.merge(
            existing = listOf(item("local:ANIME_ADDED:frieren:1", me, "Frieren", 100)),
            incoming = listOf(item("real-event", me, "Frieren !", 105)),
            selfMxid = me,
            max = 50,
        )
        // Titles differing only by punctuation are the same work.
        assertEquals(1, merged.size)
        assertEquals("real-event", merged.single().eventId)
    }

    @Test
    fun `an anime and a manga of the same title stay apart`() {
        val merged = ActivityFeedRules.merge(
            existing = listOf(item("1", me, "Berserk", 1, SocialActivityKind.ANIME_ADDED)),
            incoming = listOf(item("2", me, "Berserk", 2, SocialActivityKind.MANGA_ADDED)),
            selfMxid = me,
            max = 50,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `the oldest are dropped past the limit`() {
        val items = (1..10).map { item("e$it", friend, "Oeuvre $it", it.toLong()) }
        val merged = ActivityFeedRules.merge(emptyList(), items, me, max = 4)
        assertEquals(listOf("e10", "e9", "e8", "e7"), merged.map { it.eventId })
    }

    @Test
    fun `the feed survives a restart`(@TempDir dir: File) {
        val items = listOf(item("a", friend, "Frieren", 10), item("b", me, "Dandadan", 20, SocialActivityKind.MANGA_ADDED))
        ActivityFeedStore(dir).save(me, items)
        assertEquals(items, ActivityFeedStore(dir).load(me))
    }

    @Test
    fun `another account does not see this feed, and a missing file is an empty feed`(@TempDir dir: File) {
        ActivityFeedStore(dir).save(me, listOf(item("a", friend, "Frieren", 10)))
        assertTrue(ActivityFeedStore(dir).load("@autre:matrix.org").isEmpty())
    }
}
