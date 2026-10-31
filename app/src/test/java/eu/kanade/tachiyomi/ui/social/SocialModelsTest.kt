package eu.kanade.tachiyomi.ui.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialModelsTest {

    private val context = SocialMomentContext(
        workTitle = "Frieren",
        episodeLabel = "S1 E12",
        positionMs = 300_000L,
        durationMs = 1_440_000L,
    )

    @Test
    fun `timecode is formatted in minutes and hours`() {
        assertEquals("00:00", formatTimecode(0L))
        assertEquals("01:40", formatTimecode(100_000L))
        assertEquals("1:02:03", formatTimecode(3_723_000L))
        assertEquals("00:59", formatTimecode(59_999L))
        assertEquals("1:00:00", formatTimecode(3_600_000L))
    }

    @Test
    fun `timecode is never negative`() {
        assertEquals("00:00", formatTimecode(-1L))
        assertEquals("00:00", formatTimecode(-120_000L))
    }

    @Test
    fun `timecode handles the hour boundary and multi-hour padding`() {
        assertEquals("59:59", formatTimecode(3_599_999L))
        assertEquals("1:00:00", formatTimecode(3_600_000L))
        assertEquals("2:00:00", formatTimecode(7_200_000L))
    }

    @Test
    fun `loop window is centered on the anchor`() {
        assertEquals(45_000L..75_000L, loopWindow(60_000L, 300_000L))
    }

    @Test
    fun `loop window is clamped at the start`() {
        assertEquals(0L..20_000L, loopWindow(5_000L, 300_000L))
    }

    @Test
    fun `loop window is clamped at the end`() {
        assertEquals(280_000L..300_000L, loopWindow(295_000L, 300_000L))
    }

    @Test
    fun `loop window covers short episodes entirely`() {
        assertEquals(0L..20_000L, loopWindow(10_000L, 20_000L))
        assertEquals(0L..29_999L, loopWindow(0L, 29_999L))
        assertEquals(0L..30_000L, loopWindow(15_000L, 30_000L))
    }

    @Test
    fun `loop window with unknown duration collapses on the anchor`() {
        assertEquals(30_000L..30_000L, loopWindow(30_000L, 0L))
        assertEquals(12_000L..12_000L, loopWindow(12_000L, -1L))
    }

    @Test
    fun `loop window honors custom before and after durations`() {
        assertEquals(10_000L..30_000L, loopWindow(20_000L, 120_000L, beforeMs = 10_000L, afterMs = 10_000L))
        assertEquals(0L..12_000L, loopWindow(2_000L, 40_000L, beforeMs = 10_000L, afterMs = 10_000L))
    }

    @Test
    fun `loop window clamps anchors outside the episode`() {
        assertEquals(300_000L..300_000L, loopWindow(500_000L, 300_000L))
        assertEquals(0L..10_000L, loopWindow(-5_000L, 300_000L))
    }

    @Test
    fun `authors include friends and me`() {
        val authors = SocialDemoData.authors()

        assertEquals(listOf("kevin", "allan", "david", SocialDemoData.ME), authors.map { it.id })
        assertEquals("Toi", authors.last().name)
        assertTrue(authors.any { it.unread > 0 })
        assertTrue(authors.all { it.initials.length == 2 })
        assertTrue(authors.all { it.colorArgb != 0L })
    }

    @Test
    fun `threads are populated with valid positions`() {
        val threads = SocialDemoData.threads(context)

        assertEquals(listOf("kevin", "allan", "david"), threads.map { it.authorId })
        assertTrue(threads.all { it.messages.isNotEmpty() })
        assertTrue(threads.all { thread -> thread.audienceLabel.isNotBlank() })

        val positions = threads.flatMap { thread -> thread.messages.mapNotNull { it.positionMs } }
        assertTrue(positions.isNotEmpty())
        assertTrue(positions.all { it in 0L..context.durationMs })

        assertTrue(threads.flatMap { it.messages }.all { !it.pinned })
        assertTrue(threads.flatMap { it.messages }.any { it.reactions.isNotEmpty() })
    }

    @Test
    fun `moments are sorted and inside the episode`() {
        SocialDemoData.threads(context).forEach { thread ->
            val positions = thread.moments.map { it.positionMs }
            assertEquals(positions.sorted(), positions)
            assertTrue(positions.all { it in 0L..context.durationMs })
        }
    }

    @Test
    fun `positions stay valid for short and unknown episodes`() {
        val short = context.copy(positionMs = 2_000L, durationMs = 8_000L)
        val unknown = context.copy(positionMs = 10_000L, durationMs = 0L)

        SocialDemoData.threads(short).forEach { thread ->
            assertTrue(thread.messages.all { it.positionMs == null || it.positionMs in 0L..short.durationMs })
            assertTrue(thread.moments.all { it.positionMs in 0L..short.durationMs })
        }
        SocialDemoData.threads(unknown).forEach { thread ->
            assertTrue(thread.messages.all { it.positionMs == null || it.positionMs >= 0L })
            assertTrue(thread.moments.all { it.positionMs >= 0L })
        }
    }

    @Test
    fun `demo messages have unique ids and belong to their thread author`() {
        val threads = SocialDemoData.threads(context)
        val messages = threads.flatMap { it.messages }
        val moments = threads.flatMap { it.moments }

        assertEquals(messages.size, messages.map { it.id }.distinct().size)
        assertEquals(moments.size, moments.map { it.id }.distinct().size)
        assertTrue(threads.all { thread -> thread.messages.all { it.authorId == thread.authorId } })
    }

    @Test
    fun `my thread is empty and ready to publish`() {
        val thread = SocialDemoData.myThread(context)

        assertEquals(SocialDemoData.ME, thread.authorId)
        assertEquals("Tes amis", thread.audienceLabel)
        assertTrue(thread.messages.isEmpty())
        assertTrue(thread.moments.isEmpty())
        assertFalse(thread.messages.any { it.pinned })
    }
}
