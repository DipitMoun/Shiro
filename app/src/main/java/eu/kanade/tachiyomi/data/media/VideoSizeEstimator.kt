package eu.kanade.tachiyomi.data.media

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Estimates the download size of a video before it is downloaded.
 *
 * Direct files report their size to the server. HLS streams have no total size, so it is
 * estimated from the playlist: bandwidth × duration, or the size of the first segment times
 * the number of segments. The result is an estimate, shown as such.
 */
object VideoSizeEstimator {

    private val cache = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(MAX_PARALLEL)

    /** @return the estimated size in bytes, or null when it cannot be told. */
    suspend fun estimate(video: Video): Long? {
        val url = video.videoUrl
        if (!url.startsWith("http")) return null
        cache[url]?.let { return it }

        val size = permits.withPermit {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching {
                        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                            estimateHls(video)
                        } else {
                            estimateDirect(url, video.headers)
                        }
                    }.getOrNull()
                }
            }
        }
        return size?.takeIf { it > 0 }?.also { cache[url] = it }
    }

    private val client get() = Injekt.get<NetworkHelper>().client

    private fun estimateDirect(url: String, headers: Headers?): Long? {
        val base = Request.Builder().url(url).headers(headers ?: Headers.headersOf())
        client.newCall(base.head().build()).execute().use { response ->
            val length = response.header("Content-Length")?.toLongOrNull()
            if (response.isSuccessful && length != null && length > 0) return length
        }
        // Some servers refuse HEAD: ask for one byte and read the total from Content-Range.
        client.newCall(base.get().header("Range", "bytes=0-0").build()).execute().use { response ->
            return response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        }
    }

    private fun estimateHls(video: Video): Long? {
        val masterUrl = video.videoUrl
        val master = fetchText(masterUrl, video.headers) ?: return null

        val variants = HlsPlaylist.parseVariants(master)
        val (mediaUrl, bandwidth) = if (variants.isEmpty()) {
            masterUrl to (video.bitrate?.toLong()?.takeIf { it > 0 })
        } else {
            val variant = HlsPlaylist.chooseVariant(variants, video.resolution)
            HlsPlaylist.resolve(masterUrl, variant.uri) to variant.bandwidth
        }

        val media = if (mediaUrl == masterUrl) master else fetchText(mediaUrl, video.headers) ?: return null
        val duration = HlsPlaylist.durationSeconds(media)
        if (duration <= 0.0) return null

        if (bandwidth != null && bandwidth > 0) return (bandwidth * duration / BITS_PER_BYTE).toLong()

        // No bandwidth anywhere: scale the first segment by the segment count.
        val segments = HlsPlaylist.segmentUris(media)
        val first = segments.firstOrNull() ?: return null
        val firstSize = estimateDirect(HlsPlaylist.resolve(mediaUrl, first), video.headers) ?: return null
        return firstSize * segments.size
    }

    private fun fetchText(url: String, headers: Headers?): String? {
        val request = Request.Builder().url(url).headers(headers ?: Headers.headersOf()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string().takeIf { it.startsWith("#EXTM3U") }
        }
    }

    private const val MAX_PARALLEL = 3
    private const val TIMEOUT_MS = 12_000L
    private const val BITS_PER_BYTE = 8.0
}
