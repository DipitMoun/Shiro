package eu.kanade.tachiyomi.data.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a work really comes from for the person who shares it: the extension, its source and the
 * address of the work inside it. A friend with the same extension can open exactly that version
 * of the work instead of searching every source for a look-alike.
 *
 * It travels in the `org.shiro.room` state of a work room and in the activity payload. The field
 * names are short because activity bodies are small; older apps ignore the extra field.
 */
@Serializable
data class WorkSourceRef(
    /** `anime` or `manga`. */
    @SerialName("m") val medium: String,
    @SerialName("sid") val sourceId: Long,
    @SerialName("sn") val sourceName: String,
    /** Package of the extension that provides the source (what the friend has to install). */
    @SerialName("pkg") val pkg: String,
    /** Address of the work inside the source. */
    @SerialName("u") val url: String,
    @SerialName("t") val title: String? = null,
) : java.io.Serializable {

    val isManga: Boolean get() = medium == MANGA

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val ANIME = "anime"
        const val MANGA = "manga"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(raw: String?): WorkSourceRef? {
            if (raw.isNullOrBlank()) return null
            val ref = runCatching { JSON.decodeFromString(serializer(), raw) }.getOrNull() ?: return null
            // A reference is only usable with all of: extension, source and work address.
            return ref.takeIf { it.pkg.isNotBlank() && it.url.isNotBlank() && it.sourceId != 0L }
        }
    }
}
