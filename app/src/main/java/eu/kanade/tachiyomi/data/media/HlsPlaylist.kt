package eu.kanade.tachiyomi.data.media

import java.net.URI
import kotlin.math.abs

/** Parsing and rewriting of HLS (m3u8) playlists. Pure functions, no network. */
object HlsPlaylist {

    /** The stream uses a feature that cannot be downloaded as plain files. */
    class UnsupportedException(message: String) : Exception(message)

    data class Variant(val uri: String, val bandwidth: Long, val height: Int?)

    /** A file the playlist refers to, and the name it gets once downloaded next to the playlist. */
    data class Resource(val url: String, val localName: String)

    data class Localized(val playlist: String, val resources: List<Resource>)

    /** Variants of a master playlist; empty for a media playlist. */
    fun parseVariants(playlist: String): List<Variant> {
        val lines = playlist.lines().map { it.trim() }
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return@mapIndexedNotNull null
            val average = AVERAGE_BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull()
            // The peak bandwidth overstates the real size: discount it when it is all we have.
            val peak = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { (it * PEAK_DISCOUNT).toLong() }
            val bandwidth = average ?: peak ?: return@mapIndexedNotNull null
            val height = RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()
            Variant(uri, bandwidth, height)
        }
    }

    /** The variant closest to [preferredHeight], or the richest one when there is no preference. */
    fun chooseVariant(variants: List<Variant>, preferredHeight: Int?): Variant {
        val byHeight = preferredHeight?.let { wanted ->
            variants.filter { it.height != null }.minByOrNull { abs(it.height!! - wanted) }
        }
        return byHeight ?: variants.maxBy { it.bandwidth }
    }

    /** Total duration of a media playlist, from its EXTINF entries. */
    fun durationSeconds(playlist: String): Double =
        EXTINF.findAll(playlist).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

    fun segmentUris(playlist: String): List<String> =
        playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun resolve(base: String, reference: String): String =
        runCatching { URI(base).resolve(reference).toString() }.getOrDefault(reference)

    /**
     * Rewrites a VOD media playlist so every segment, key and init section is a local file.
     *
     * @throws UnsupportedException for live streams, byte ranges and encryption other than
     * AES-128: those are left to ffmpeg.
     */
    fun localize(playlist: String, baseUrl: String): Localized {
        require(playlist.startsWith("#EXTM3U")) { "not a playlist" }
        if (!playlist.contains("#EXT-X-ENDLIST")) throw UnsupportedException("live stream")

        val resources = LinkedHashMap<String, Resource>()
        var segments = 0
        var keys = 0
        var inits = 0

        fun local(uri: String, nameFor: () -> String): String {
            val url = resolve(baseUrl, uri)
            return resources.getOrPut(url) { Resource(url, nameFor()) }.localName
        }

        val output = playlist.lines().map { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-BYTERANGE") -> throw UnsupportedException("byte ranges")
                line.startsWith("#EXT-X-KEY") -> {
                    val method = METHOD.find(line)?.groupValues?.get(1)
                    when (method) {
                        null, "NONE" -> line
                        "AES-128" -> replaceUri(line) { uri -> local(uri) { "key_${keys++}.bin" } }
                        else -> throw UnsupportedException("encryption $method")
                    }
                }
                line.startsWith("#EXT-X-MAP") -> replaceUri(line) { uri -> local(uri) { "init_${inits++}.${extensionOf(uri, "mp4")}" } }
                line.isEmpty() || line.startsWith("#") -> line
                else -> local(line) { "seg_%05d.%s".format(segments++, extensionOf(line, "ts")) }
            }
        }
        return Localized(output.joinToString("\n"), resources.values.toList())
    }

    private fun replaceUri(line: String, transform: (String) -> String): String =
        URI_ATTRIBUTE.replace(line) { match -> "URI=\"${transform(match.groupValues[1])}\"" }

    private fun extensionOf(uri: String, default: String): String {
        val name = uri.substringBefore('?').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        return ext.takeIf { it.length in 1..4 && it.all(Char::isLetterOrDigit) } ?: default
    }

    private val BANDWIDTH = Regex("(?<![-A-Z])BANDWIDTH=(\\d+)")
    private val AVERAGE_BANDWIDTH = Regex("AVERAGE-BANDWIDTH=(\\d+)")
    private val RESOLUTION = Regex("RESOLUTION=(\\d+)x(\\d+)")
    private val EXTINF = Regex("#EXTINF:([0-9.]+)")
    private val METHOD = Regex("METHOD=([A-Z0-9-]+)")
    private val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"")

    private const val PEAK_DISCOUNT = 0.85
}
