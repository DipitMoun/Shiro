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
            key = "S01E003",
            number = 3.0,
            season = 1,
            kind = "regular",
            numbering = "season",
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
        val decoded = codec.decode(codec.encode(payload.copy(v = 2)))

        assertInvalid(decoded, "Unsupported payload version")
    }

    @Test
    fun `missing required fields are rejected`() {
        val decoded = codec.decode(
            """{"work":{"id":"anilist:1"},"episode":{"key":"S01E001"},"moments":[{"positionMs":0}],"source":{"key":"x"},"capturedAt":"2026-10-30T19:42:11Z"}""",
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
            """{"v":1,"work":{"id":"anilist:1"},"episode":{"key":"S01E001"},"moments":[{"positionMs":0}],"source":{"key":"x"},"capturedAt":"2026-10-30T19:42:11Z"}""",
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
        assertTrue(body.contains("S01E003"))
        assertTrue(body.contains("musique"))
    }

    private fun assertInvalid(result: MomentDecodeResult, expectedReason: String) {
        assertTrue(result is MomentDecodeResult.Invalid)
        assertTrue(
            (result as MomentDecodeResult.Invalid).reasons.any { it.contains(expectedReason, ignoreCase = true) },
            "Expected reason '$expectedReason' in ${result.reasons}",
        )
    }
}
