package eu.kanade.tachiyomi.data.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,AVERAGE-BANDWIDTH=1500000,RESOLUTION=1280x720
        720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360
        360/index.m3u8
    """.trimIndent()

    @Test
    fun `variants use the average bandwidth when present`() {
        val variants = HlsPlaylist.parseVariants(master)

        assertEquals(2, variants.size)
        assertEquals(HlsPlaylist.Variant("720/index.m3u8", 1_500_000, 720), variants[0])
    }

    @Test
    fun `peak-only bandwidth is discounted`() {
        val variant = HlsPlaylist.parseVariants(master)[1]

        assertEquals("360/index.m3u8", variant.uri)
        assertEquals(765_000, variant.bandwidth)
        assertEquals(360, variant.height)
    }

    @Test
    fun `the variant closest to the wanted height wins, else the richest`() {
        val variants = HlsPlaylist.parseVariants(master)

        assertEquals("360/index.m3u8", HlsPlaylist.chooseVariant(variants, 400).uri)
        assertEquals("720/index.m3u8", HlsPlaylist.chooseVariant(variants, null).uri)
    }

    @Test
    fun `a media playlist has no variants`() {
        val media = "#EXTM3U\n#EXTINF:10.0,\nseg1.ts\n#EXTINF:10.0,\nseg2.ts\n#EXT-X-ENDLIST"

        assertTrue(HlsPlaylist.parseVariants(media).isEmpty())
    }

    @Test
    fun `duration is the sum of segment durations and segments are listed in order`() {
        val media = "#EXTM3U\n#EXTINF:9.5,\nseg1.ts\n#EXTINF:10.5,\nseg2.ts\n#EXTINF:4,\nseg3.ts\n#EXT-X-ENDLIST"

        assertEquals(24.0, HlsPlaylist.durationSeconds(media), 0.001)
        assertEquals(listOf("seg1.ts", "seg2.ts", "seg3.ts"), HlsPlaylist.segmentUris(media))
    }

    @Test
    fun `localize points every segment to a local file and resolves urls against the playlist`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:10.0,
            seg1.ts?token=abc
            #EXTINF:10.0,
            https://cdn.example.org/other/seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsPlaylist.localize(media, "https://host.example.org/v/720/index.m3u8")

        assertEquals(
            listOf(
                HlsPlaylist.Resource("https://host.example.org/v/720/seg1.ts?token=abc", "seg_00000.ts"),
                HlsPlaylist.Resource("https://cdn.example.org/other/seg2.ts", "seg_00001.ts"),
            ),
            result.resources,
        )
        assertTrue(result.playlist.contains("seg_00000.ts"))
        assertTrue(result.playlist.contains("seg_00001.ts"))
        assertTrue(result.playlist.contains("#EXT-X-VERSION:3"))
    }

    @Test
    fun `aes-128 key and init section become local files, the iv is kept`() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example.org/key.bin",IV=0x0001
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6,
            a.m4s
            #EXTINF:6,
            b.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsPlaylist.localize(media, "https://host.example.org/p/index.m3u8")

        assertTrue(result.playlist.contains("""#EXT-X-KEY:METHOD=AES-128,URI="key_0.bin",IV=0x0001"""))
        assertTrue(result.playlist.contains("""#EXT-X-MAP:URI="init_0.mp4""""))
        assertEquals(
            listOf("key_0.bin", "init_0.mp4", "seg_00000.m4s", "seg_00001.m4s"),
            result.resources.map { it.localName },
        )
    }

    @Test
    fun `the same url is downloaded once`() {
        val media = "#EXTM3U\n#EXTINF:5,\nsame.ts\n#EXTINF:5,\nsame.ts\n#EXT-X-ENDLIST"

        assertEquals(1, HlsPlaylist.localize(media, "https://h.example.org/i.m3u8").resources.size)
    }

    @Test
    fun `live streams, byte ranges and unsupported encryption are left to ffmpeg`() {
        val live = "#EXTM3U\n#EXTINF:5,\nseg.ts"
        val ranges = "#EXTM3U\n#EXT-X-BYTERANGE:100@0\n#EXTINF:5,\nseg.ts\n#EXT-X-ENDLIST"
        val sample = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:5,\nseg.ts\n#EXT-X-ENDLIST"

        listOf(live, ranges, sample).forEach { playlist ->
            assertThrows(HlsPlaylist.UnsupportedException::class.java) {
                HlsPlaylist.localize(playlist, "https://h.example.org/i.m3u8")
            }
        }
    }
}
