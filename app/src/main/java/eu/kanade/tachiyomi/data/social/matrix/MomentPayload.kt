package eu.kanade.tachiyomi.data.social.matrix

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Payload carried by a Matrix `m.room.message` under `org.shiro.moment`.
 *
 * The codec only handles the payload object. Matrix relation handling
 * (`m.thread`, `m.replace`, redactions) belongs to the event layer.
 */
@Serializable
data class MomentPayload(
    val v: Int,
    val work: MomentWork,
    val episode: MomentEpisode,
    val moments: List<MomentAnchor>,
    val note: String? = null,
    val source: MomentSource,
    val capturedAt: String,
    val spoiler: Boolean = false,
    val client: MomentClient? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class MomentWork(
    val id: String,
    val ids: Map<String, String> = emptyMap(),
    val title: String? = null,
    val format: String? = null,
    val year: Int? = null,
)

@Serializable
data class MomentEpisode(
    val key: String,
    val number: Double? = null,
    val season: Int? = null,
    val kind: String? = null,
    val numbering: String? = null,
    val title: String? = null,
)

@Serializable
data class MomentAnchor(
    val positionMs: Long,
    val label: String? = null,
    val lang: String? = null,
    val beforeMs: Long? = null,
    val afterMs: Long? = null,
)

@Serializable
data class MomentSource(
    val key: String,
    val kind: String? = null,
    val lang: String? = null,
    val durationMs: Long? = null,
)

@Serializable
data class MomentClient(
    val name: String,
    val version: String,
)

sealed interface MomentDecodeResult {
    data class Valid(val payload: MomentPayload) : MomentDecodeResult

    data class Invalid(val reasons: List<String>) : MomentDecodeResult
}

object MomentPayloadCodec {
    private val workId = Regex("^(anilist|mal|anidb|tvdb|tg|local):[A-Za-z0-9._:-]{1,128}$")
    private val episodeKey = Regex("^(S[0-9]{2}E[0-9]{3,4}(\\.[0-9]{1,2})?|MAIN)$")
    private val formats = setOf("TV", "MOVIE", "OVA", "ONA", "SPECIAL", "OTHER")
    private val kinds = setOf("regular", "special", "film", "ova", "recap", "unknown")
    private val numberings = setOf("season", "absolute", "unknown")
    private val sourceKinds = setOf("stream", "download", "local", "telegram")

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(payload: MomentPayload): String = json.encodeToString(payload)

    fun decode(raw: String): MomentDecodeResult {
        val payload = try {
            json.decodeFromString<MomentPayload>(raw)
        } catch (error: SerializationException) {
            return MomentDecodeResult.Invalid(listOf(error.message ?: "Invalid JSON payload"))
        } catch (error: IllegalArgumentException) {
            return MomentDecodeResult.Invalid(listOf(error.message ?: "Invalid JSON payload"))
        }

        val reasons = validate(payload)
        return if (reasons.isEmpty()) {
            MomentDecodeResult.Valid(payload)
        } else {
            MomentDecodeResult.Invalid(reasons)
        }
    }

    fun validate(payload: MomentPayload): List<String> = buildList {
        if (payload.v != MomentPayload.VERSION) {
            add("Unsupported payload version: ${payload.v}")
        }
        if (!workId.matches(payload.work.id)) {
            add("Invalid work id")
        }
        if (payload.work.ids.values.any { it.isBlank() }) {
            add("Work aliases must not be blank")
        }
        if (payload.work.title != null) {
            if (payload.work.title.length !in 1..200) add("Work title must be 1..200 characters")
        }
        if (payload.work.format != null && payload.work.format !in formats) {
            add("Invalid work format")
        }
        if (payload.work.year != null && payload.work.year !in 1900..2200) {
            add("Work year is outside 1900..2200")
        }

        if (!episodeKey.matches(payload.episode.key)) {
            add("Invalid episode key")
        }
        if (payload.episode.number != null && payload.episode.number < 0.0) {
            add("Episode number must be positive")
        }
        if (payload.episode.season != null && payload.episode.season < 0) {
            add("Episode season must be positive")
        }
        if (payload.episode.kind != null && payload.episode.kind !in kinds) {
            add("Invalid episode kind")
        }
        if (payload.episode.numbering != null && payload.episode.numbering !in numberings) {
            add("Invalid episode numbering")
        }
        if (payload.episode.title != null && payload.episode.title.length > 200) {
            add("Episode title is too long")
        }

        if (payload.moments.size !in 1..5) {
            add("Moments must contain between 1 and 5 anchors")
        }
        payload.moments.forEachIndexed { index, moment ->
            if (moment.positionMs < 0L) add("Moment $index has a negative position")
            if (moment.label != null && moment.label.length > 280) add("Moment $index label is too long")
            if (moment.beforeMs != null && moment.beforeMs !in 0L..60000L) {
                add("Moment $index beforeMs is outside 0..60000")
            }
            if (moment.afterMs != null && moment.afterMs !in 0L..60000L) {
                add("Moment $index afterMs is outside 0..60000")
            }
        }

        if (payload.note != null && payload.note.length > 2000) {
            add("Note is too long")
        }
        if (payload.source.key.length !in 1..256) {
            add("Source key must be 1..256 characters")
        }
        if (payload.source.kind != null && payload.source.kind !in sourceKinds) {
            add("Invalid source kind")
        }
        if (payload.source.durationMs != null && payload.source.durationMs <= 0L) {
            add("Source duration must be positive")
        }
        try {
            Instant.parse(payload.capturedAt)
        } catch (_: Exception) {
            add("capturedAt is not an ISO-8601 instant")
        }
        payload.client?.let { client ->
            if (client.name.length !in 1..64) add("Client name must be 1..64 characters")
            if (client.version.length !in 1..64) add("Client version must be 1..64 characters")
        }
    }

    fun fallbackBody(payload: MomentPayload): String {
        val times = payload.moments.joinToString(", ") { formatTimecode(it.positionMs) }
        val work = payload.work.title ?: payload.work.id
        return buildString {
            append("▶ ")
            append(times)
            payload.note?.takeIf { it.isNotBlank() }?.let { note ->
                append(" — ")
                append(note)
            }
            append(" (")
            append(work)
            append(" · ")
            append(payload.episode.key)
            append(")")
        }
    }

    private fun formatTimecode(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        val minutes = ((totalSeconds / 60) % 60).toString().padStart(2, '0')
        return if (totalSeconds >= 3600) {
            "${totalSeconds / 3600}:$minutes:$seconds"
        } else {
            "${(totalSeconds / 60).toString().padStart(2, '0')}:$seconds"
        }
    }
}
