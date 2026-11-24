package eu.kanade.tachiyomi.data.social.matrix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MomentPayloadTest {

    private val payload = MomentPayload(
        v = 1,
        work = MomentWork(
            id = "anilist:154587",
            ids = mapOf("anilist" to "154587", "mal" to "52991"),
            title = "Sousou no Frieren",
            format = "TV",
            year = 2023,
        ),
        episode = MomentEpisode(
            key = "E003",
            number = 3.0,
            season = null,
            kind = "regular",
            numbering = "unknown",
            title = "Le pays des mages",
        ),
        moments = listOf(
            MomentAnchor(positionMs = 754_000L),
            MomentAnchor(positionMs = 1_082_000L, label = "La musique.", lang = "fr"),
        ),
        note = "Ce plan-là, et la musique juste après.",
        source = MomentSource(
            key = "aniyomi:123456:fr",
            kind = "stream",
            lang = "fr",
            durationMs = 1_440_000L,
        ),
        capturedAt = "2026-10-30T19:42:11Z",
        spoiler = false,
        client = MomentClient(name = "Shiro", version = "0.18.2.1"),
    )

    private val codec = MomentPayloadCodec

    @Test
    fun `payload round trips through json`() {
        val encoded = codec.encode(payload)

        val decoded = codec.decode(encoded)

        assertTrue(decoded is MomentDecodeResult.Valid)
        assertEquals(payload, (decoded as MomentDecodeResult.Valid).payload)
    }

    @Test
    fun `unsupported version is rejected`() {
        val decoded = codec.decode(codec.encode(payload.copy(v = 3)))

        assertInvalid(decoded, "Unsupported payload version")
    }

    @Test
    fun `missing required fields are rejected`() {
        val decoded = codec.decode(
            """{"work":{"id":"anilist:1"},"episode":{"key":"E001"},"moments":[{"positionMs":0}],"source":{"key":"x"},"capturedAt":"2026-10-30T19:42:11Z"}""",
        )

        assertTrue(decoded is MomentDecodeResult.Invalid)
    }

    @Test
    fun `unknown fields are rejected`() {
        val encoded = codec.encode(payload).replaceFirst("{\"v\":1,", "{\"extra\":1,\"v\":1,")

        assertInvalid(codec.decode(encoded), "extra")
    }

    @Test
    fun `moments count is limited to one through five`() {
        assertInvalid(codec.decode(codec.encode(payload.copy(moments = emptyList()))), "1 and 5")
        assertInvalid(
            codec.decode(codec.encode(payload.copy(moments = List(6) { MomentAnchor(it.toLong()) }))),
            "1 and 5",
        )
    }

    @Test
    fun `invalid work episode and positions are rejected`() {
        assertInvalid(
            codec.decode(codec.encode(payload.copy(work = payload.work.copy(id = "not-a-work")))),
            "Invalid work id",
        )
        assertInvalid(
            codec.decode(codec.encode(payload.copy(episode = payload.episode.copy(key = "S1E003")))),
            "Invalid episode key",
        )
        assertInvalid(
            codec.decode(codec.encode(payload.copy(moments = listOf(MomentAnchor(-1L))))),
            "negative position",
        )
    }

    @Test
    fun `optional fields receive schema defaults`() {
        val decoded = codec.decode(
            """{"v":1,"work":{"id":"anilist:1"},"episode":{"key":"E001"},"moments":[{"positionMs":0}],"source":{"key":"x"},"capturedAt":"2026-10-30T19:42:11Z"}""",
        )

        assertTrue(decoded is MomentDecodeResult.Valid)
        val value = (decoded as MomentDecodeResult.Valid).payload
        assertEquals(false, value.spoiler)
        assertEquals(null, value.note)
        assertEquals(null, value.client)
        assertEquals(emptyMap<String, String>(), value.work.ids)
    }

    @Test
    fun `fallback body contains timecodes work and episode`() {
        val body = codec.fallbackBody(payload)

        assertTrue(body.contains("12:34"))
        assertTrue(body.contains("18:02"))
        assertTrue(body.contains("Sousou no Frieren"))
        assertTrue(body.contains("E003"))
        assertTrue(body.contains("musique"))
    }

    private fun assertInvalid(result: MomentDecodeResult, expectedReason: String) {
        assertTrue(result is MomentDecodeResult.Invalid)
        assertTrue(
            (result as MomentDecodeResult.Invalid).reasons.any { it.contains(expectedReason, ignoreCase = true) },
            "Expected reason '$expectedReason' in ${result.reasons}",
        )
    }

    private val readingPayload = MomentPayload(
        v = MomentPayload.PAGE_VERSION,
        work = MomentWork(id = "local:abc123", title = "Omniscient Reader", format = "MANGA"),
        episode = MomentEpisode(key = "E045", number = 45.0, title = "Chapitre 45"),
        moments = listOf(MomentAnchor(page = 12), MomentAnchor(page = 30, label = "La case finale")),
        note = "Regarde la page 12.",
        source = MomentSource(key = "local:abc123", kind = "reader"),
        capturedAt = "2026-11-24T21:00:00Z",
        client = MomentClient(name = "Shiro", version = "0.19.0"),
    )

    @Test
    fun `a reading moment round trips and keeps its pages`() {
        val decoded = codec.decode(codec.encode(readingPayload))

        assertTrue(decoded is MomentDecodeResult.Valid)
        val valid = (decoded as MomentDecodeResult.Valid).payload
        assertEquals(listOf(12, 30), valid.moments.map { it.page })
        assertTrue(valid.isPageMoment)
    }

    @Test
    fun `a reading moment needs a page on every anchor`() {
        val noPage = readingPayload.copy(moments = listOf(MomentAnchor(positionMs = 5_000L)))

        assertInvalid(codec.decode(codec.encode(noPage)), "needs a page")
    }

    @Test
    fun `a page outside the allowed range is rejected`() {
        val bad = readingPayload.copy(moments = listOf(MomentAnchor(page = 0)))

        assertInvalid(codec.decode(codec.encode(bad)), "needs a page")
    }

    @Test
    fun `a video moment cannot carry a page`() {
        val mixed = payload.copy(moments = listOf(MomentAnchor(positionMs = 1_000L, page = 3)))

        assertInvalid(codec.decode(codec.encode(mixed)), "not a reading moment")
    }

    @Test
    fun `the text shown to older apps names the pages`() {
        val body = codec.fallbackBody(readingPayload)

        assertTrue(body.contains("p. 12, p. 30"), body)
        assertTrue(body.contains("Regarde la page 12."), body)
        assertTrue(body.contains("Omniscient Reader"), body)
    }
}
