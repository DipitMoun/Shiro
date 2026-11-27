package eu.kanade.tachiyomi.data.social.story

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class StoryRulesTest {

    private val mb = StoryLimits.MB
    private val now = 1_800_000_000_000L
    private fun ago(hours: Long) = now - TimeUnit.HOURS.toMillis(hours)

    // ---- quota

    @Test
    fun `used and remaining are counted per window`() {
        val records = listOf(
            SentRecord(ago(1), 30 * mb),
            SentRecord(ago(20), 20 * mb),
            SentRecord(ago(30), 40 * mb), // out of the day, still in the month
        )

        val status = StoryQuota.status(records, now)

        assertEquals(50 * mb, status.usedDay)
        assertEquals(90 * mb, status.usedMonth)
        assertEquals(50 * mb, status.remainingDay)
        assertEquals(410 * mb, status.remainingMonth)
        assertEquals(50 * mb, status.remaining)
    }

    @Test
    fun `sends older than the window no longer count`() {
        val records = listOf(SentRecord(now - StoryLimits.MONTH_MS - 1, 400 * mb))

        assertEquals(0, StoryQuota.status(records, now).usedMonth)
    }

    @Test
    fun `a small send is allowed`() {
        assertEquals(SendVerdict.Allowed, StoryQuota.verdict(emptyList(), now, 3 * mb))
    }

    @Test
    fun `one attachment over the file limit is refused whatever the quota`() {
        val verdict = StoryQuota.verdict(emptyList(), now, 12 * mb)

        assertEquals(SendVerdict.FileTooBig(12 * mb, 10 * mb), verdict)
    }

    @Test
    fun `the server limit can be lower than ten megabytes`() {
        val verdict = StoryQuota.verdict(emptyList(), now, 6 * mb, maxFileBytes = 5 * mb)

        assertTrue(verdict is SendVerdict.FileTooBig)
    }

    @Test
    fun `the day quota blocks and says when it comes back`() {
        val records = listOf(SentRecord(ago(10), 60 * mb), SentRecord(ago(2), 35 * mb))

        val verdict = StoryQuota.verdict(records, now, 9 * mb) as SendVerdict.QuotaExceeded

        assertEquals(QuotaWindow.DAY, verdict.reason)
        // 5 MB left: the 60 MB sent 10 h ago leaves the window 14 h from now.
        assertEquals(ago(10) + StoryLimits.DAY_MS, verdict.retryAtMillis)
    }

    @Test
    fun `the month quota blocks when the day is fine`() {
        val records = (1..5).map { SentRecord(now - TimeUnit.DAYS.toMillis(it * 4L), 99 * mb) } // 495 MB
        val verdict = StoryQuota.verdict(records, now, 9 * mb) as SendVerdict.QuotaExceeded

        assertEquals(QuotaWindow.MONTH, verdict.reason)
        assertTrue(verdict.retryAtMillis!! > now)
    }

    @Test
    fun `a big send that fits asks for confirmation`() {
        // The 10 MB file rule is lifted here to look at the 50 MB confirmation alone.
        val verdict = StoryQuota.verdict(emptyList(), now, 60 * mb, maxFileBytes = 100 * mb)

        assertEquals(SendVerdict.NeedsConfirmation(60 * mb), verdict)
    }

    @Test
    fun `nothing returns for a send larger than the window`() {
        assertNull(StoryQuota.releaseTime(emptyList(), now, StoryLimits.DAY_MS, StoryLimits.DAILY_BYTES, 101 * mb))
    }

    // ---- expiry

    @Test
    fun `a story lives twenty four hours`() {
        assertTrue(StoryExpiry.isActive(ago(23), now))
        assertFalse(StoryExpiry.isActive(ago(25), now))
        assertEquals(ago(1) + StoryLimits.TTL_MS, StoryExpiry.expiresAt(ago(1)))
    }

    @Test
    fun `how long ago reads short`() {
        assertEquals("22 s", StoryExpiry.ago(now - 22_000, now))
        assertEquals("3 min", StoryExpiry.ago(now - 3 * 60_000, now))
        assertEquals("5 h", StoryExpiry.ago(ago(5), now))
    }

    // ---- music story

    @Test
    fun `a music story is only a reference`() {
        val theme = ThemeRef(anilistId = 154587, themeId = 8421, startSeconds = 42, label = "Frieren OP1")

        assertEquals(theme, ThemeRef.decode(theme.encode()))
        assertFalse(theme.encode().contains("http"))
    }

    @Test
    fun `an unusable theme reference is ignored`() {
        assertNull(ThemeRef.decode("nope"))
        assertNull(ThemeRef.decode("""{"a":0,"th":5}"""))
        assertNull(ThemeRef.decode("""{"a":5,"th":0}"""))
        assertNull(ThemeRef.decode("""{"a":5,"th":6,"s":-3}"""))
    }

    // ---- reels

    private fun story(author: String, id: String, postedAt: Long) = StoryItem(
        eventId = id,
        authorMxid = author,
        postedAtMillis = postedAt,
        caption = null,
        content = StoryContent.Music(ThemeRef(1, 2)),
    )

    @Test
    fun `stories are grouped by person, expired ones dropped, own reel first`() {
        val items = listOf(
            story("@bob:x", "b1", ago(3)),
            story("@bob:x", "b2", ago(1)),
            story("@old:x", "o1", ago(30)),
            story("@me:x", "m1", ago(2)),
            story("@amy:x", "a1", ago(5)),
        )

        val reels = StoryReels.build(items, now, emptyMap(), "@me:x")

        assertEquals(listOf("@me:x", "@bob:x", "@amy:x"), reels.map { it.authorMxid })
        assertEquals(listOf("b1", "b2"), reels[1].items.map { it.eventId })
    }

    @Test
    fun `a reel is unseen until the latest story is watched`() {
        val items = listOf(story("@bob:x", "b1", ago(3)), story("@bob:x", "b2", ago(1)))

        assertTrue(StoryReels.build(items, now, mapOf("@bob:x" to ago(3)), "@me:x").single().hasUnseen)
        assertFalse(StoryReels.build(items, now, mapOf("@bob:x" to ago(1)), "@me:x").single().hasUnseen)
    }

    @Test
    fun `reels with something new come before the watched ones`() {
        val items = listOf(story("@amy:x", "a1", ago(1)), story("@bob:x", "b1", ago(6)))

        val reels = StoryReels.build(items, now, mapOf("@amy:x" to ago(1)), "@me:x")

        assertEquals(listOf("@bob:x", "@amy:x"), reels.map { it.authorMxid })
    }
}
