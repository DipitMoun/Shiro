package eu.kanade.tachiyomi.data.social.matrix.timeline

import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import eu.kanade.tachiyomi.data.social.matrix.TransportEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageProjectionTest {

    private fun event(content: String, readBy: List<String> = emptyList()) = TransportEvent(
        eventId = "\$img",
        roomId = "!room:example.org",
        sender = "@bob:example.org",
        type = "m.room.message",
        originServerTs = 1_000,
        contentJson = content,
        readBy = readBy,
    )

    private fun project(content: String, readBy: List<String> = emptyList()): SocialTimelineItem.ReplyItem {
        val state = ProjectingMomentRepository.MutableThreadState()
        state.apply(listOf(event(content, readBy)))
        return state.decoratedItems().single() as SocialTimelineItem.ReplyItem
    }

    @Test
    fun `an image without caption keeps no text, only the picture`() {
        val item = project(
            """{"msgtype":"m.image","body":"IMG_2041.jpg","url":"mxc://example.org/abc",
               "info":{"mimetype":"image/jpeg","w":800,"h":600}}""",
        )

        assertEquals("", item.body)
        val media = item.media!!
        assertEquals("mxc://example.org/abc", media.url)
        assertEquals(800, media.width)
        assertEquals(600, media.height)
        assertNull(media.encryptedFileJson)
    }

    @Test
    fun `a caption is kept when the file name is given separately`() {
        val item = project(
            """{"msgtype":"m.image","body":"Cette page !","filename":"p12.png","url":"mxc://e/x",
               "info":{"mimetype":"image/png"}}""",
        )

        assertEquals("Cette page !", item.body)
    }

    @Test
    fun `an animated gif is recognised`() {
        val item = project("""{"msgtype":"m.image","body":"a.gif","url":"mxc://e/g","info":{"mimetype":"image/gif"}}""")

        assertTrue(item.media!!.isGif)
    }

    @Test
    fun `an encrypted image carries the file description to decrypt it`() {
        val item = project(
            """{"msgtype":"m.image","body":"photo.jpg","file":{"url":"mxc://e/enc","key":{"k":"abc"}},
               "info":{"mimetype":"image/jpeg"}}""",
        )

        assertNull(item.media!!.url)
        assertNotNull(item.media!!.encryptedFileJson)
        assertTrue(item.media!!.encryptedFileJson!!.contains("mxc://e/enc"))
    }

    @Test
    fun `a plain text message has no media`() {
        assertNull(project("""{"msgtype":"m.text","body":"salut"}""").media)
    }

    @Test
    fun `read receipts travel with the message`() {
        val item = project("""{"msgtype":"m.text","body":"salut"}""", readBy = listOf("@alice:example.org"))

        assertEquals(listOf("@alice:example.org"), item.readBy)
    }
}
