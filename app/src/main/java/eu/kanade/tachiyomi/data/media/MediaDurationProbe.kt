package eu.kanade.tachiyomi.data.media

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.resume

/**
 * Best-effort remote duration probe used to filter out short videos
 * (openings, teasers, previews) before they reach the user.
 *
 * Strategy:
 * 1. HLS (.m3u8): fetch the playlist; if VOD, sum #EXTINF durations.
 * 2. MP4/WebM/MKV: ffprobe (FFmpegKit) with a small probesize.
 * 3. Anything else or any failure: return null (caller keeps the media).
 *
 * Runs the two probe kinds in parallel; total worst case is around 8 s
 * per file. All errors are swallowed and logged, never thrown.
 */
object MediaDurationProbe {

    private const val HLS_TIMEOUT_MS = 4_000L
    private const val FFPROBE_TIMEOUT_MS = 8_000L
    private const val EXTINF_PREFIX = "#EXTINF:"
    private const val STREAM_INF = "#EXT-X-STREAM-INF"
    private const val ENDLIST = "#EXT-X-ENDLIST"

    private val network: NetworkHelper by lazy { Injekt.get() }

    suspend fun probeDuration(url: String, headers: Map<String, String>): Long? {
        if (url.isBlank()) return null
        if (url.startsWith("blob:") || url.startsWith("data:")) return null
        return runCatching {
            if (url.contains(".m3u8", ignoreCase = true)) {
                withTimeoutOrNull(HLS_TIMEOUT_MS) { probeHls(url, headers) }
            } else {
                withTimeoutOrNull(FFPROBE_TIMEOUT_MS) { probeWithFfprobe(url, headers) }
            }
        }
            .onFailure {
                logcat(LogPriority.DEBUG, it) { "MediaDurationProbe: probe failed $url" }
            }
            .getOrNull()
    }

    private suspend fun probeHls(
        url: String,
        headers: Map<String, String>,
    ): Long? = withContext(Dispatchers.IO) {
        var body = fetchPlaylist(url, headers) ?: return@withContext null
        // If master playlist, follow the first variant
        if (body.contains(STREAM_INF)) {
            val variant = firstVariantUrl(body) ?: return@withContext null
            val absolute = runCatching { java.net.URI(url).resolve(variant).toString() }
                .getOrDefault(variant)
            body = fetchPlaylist(absolute, headers) ?: return@withContext null
        }
        // Live streams have no #EXT-X-ENDLIST; cannot measure, return null
        if (!body.contains(ENDLIST)) return@withContext null
        var totalSeconds = 0.0
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(EXTINF_PREFIX)) {
                val duration = trimmed.removePrefix(EXTINF_PREFIX)
                    .substringBefore(',').toDoubleOrNull()
                if (duration != null) totalSeconds += duration
            }
        }
        totalSeconds.toLong().takeIf { it > 0L }
    }

    private fun fetchPlaylist(url: String, headers: Map<String, String>): String? {
        val requestBuilder = Request.Builder().url(url)
        if (headers.isNotEmpty()) {
            val hb = Headers.Builder()
            headers.forEach { (k, v) -> hb.add(k, v) }
            requestBuilder.headers(hb.build())
        }
        return runCatching {
            network.client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.onFailure {
            logcat(LogPriority.DEBUG, it) { "MediaDurationProbe: fetch failed $url" }
        }.getOrNull()
    }

    private fun firstVariantUrl(playlist: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith(STREAM_INF) && i + 1 < lines.size) {
                val next = lines[i + 1]
                if (next.isNotBlank() && !next.startsWith("#")) return next
            }
        }
        return null
    }

    private suspend fun probeWithFfprobe(
        url: String,
        headers: Map<String, String>,
    ): Long? {
        val headerOptions = if (headers.isEmpty()) {
            ""
        } else {
            headers.entries.joinToString("", prefix = "-headers '", postfix = "' ") {
                "${it.key}: ${it.value}\\r\\n"
            }
        }
        val args = FFmpegKitConfig.parseArguments(
            "${headerOptions}-analyzeduration 5M -probesize 5M -v quiet " +
                "-show_entries format=duration " +
                "-of default=noprint_wrappers=1:nokey=1 \"$url\"",
        )
        val output = suspendCancellableCoroutine<String?> { continuation ->
            val session = FFprobeKit.executeWithArgumentsAsync(args) {
                if (it.returnCode.isValueSuccess) {
                    continuation.resume(it.output)
                } else {
                    continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { session.cancel() }
        } ?: return null
        return output.trim().toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
    }
}