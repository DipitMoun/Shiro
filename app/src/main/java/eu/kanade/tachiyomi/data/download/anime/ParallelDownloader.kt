package eu.kanade.tachiyomi.data.download.anime

import eu.kanade.tachiyomi.data.media.HlsPlaylist
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Downloads a video over several connections at once, the way a download manager does.
 *
 * A single connection is what limits the speed on a weak network: HLS segments arrive one by
 * one, and a direct file comes through one stream. Here HLS segments, or slices of a direct
 * file, are fetched in parallel into a local folder. The caller then hands the local result
 * to ffmpeg, which only has to remux it.
 *
 * Anything this class cannot handle throws [HlsPlaylist.UnsupportedException] (or fails with an
 * [IOException]); the caller falls back to the plain ffmpeg download.
 */
class ParallelDownloader(
    private val client: OkHttpClient,
    private val connections: Int = DEFAULT_CONNECTIONS,
) {

    /**
     * @param fraction 0..1 of the download phase
     * @param bytes bytes written so far
     * @param estimatedTotalBytes 0 while unknown
     */
    class Progress(val fraction: Double, val bytes: Long, val estimatedTotalBytes: Long)

    /** Downloads an HLS stream; returns the local playlist, ready for ffmpeg. */
    suspend fun downloadHls(
        playlistUrl: String,
        headers: Headers,
        preferredHeight: Int?,
        workDir: File,
        onProgress: (Progress) -> Unit,
    ): File {
        workDir.mkdirs()

        var mediaUrl = playlistUrl
        var media = fetchText(playlistUrl, headers)
        val variants = HlsPlaylist.parseVariants(media)
        if (variants.isNotEmpty()) {
            mediaUrl = HlsPlaylist.resolve(playlistUrl, HlsPlaylist.chooseVariant(variants, preferredHeight).uri)
            media = fetchText(mediaUrl, headers)
        }

        val localized = HlsPlaylist.localize(media, mediaUrl)
        if (localized.resources.isEmpty()) throw IOException("empty playlist")

        val total = localized.resources.size
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val gate = Semaphore(connections)

        coroutineScope {
            localized.resources.map { resource ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        bytes.addAndGet(fetchToFile(resource.url, headers, File(workDir, resource.localName)))
                        val finished = done.incrementAndGet()
                        val written = bytes.get()
                        // The total is extrapolated once a few segments give a fair average.
                        val estimate = if (finished >= MIN_SEGMENTS_FOR_ESTIMATE) written * total / finished else 0L
                        onProgress(Progress(finished.toDouble() / total, written, estimate))
                    }
                }
            }.awaitAll()
        }

        return File(workDir, LOCAL_PLAYLIST).also { it.writeText(localized.playlist) }
    }

    /** Downloads a direct file in parallel slices; returns the merged local file. */
    suspend fun downloadFile(
        url: String,
        headers: Headers,
        workDir: File,
        onProgress: (Progress) -> Unit,
    ): File {
        workDir.mkdirs()

        val length = client.newCall(Request.Builder().url(url).headers(headers).head().build()).await().use {
            val acceptsRanges = it.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            if (!it.isSuccessful || !acceptsRanges) {
                throw HlsPlaylist.UnsupportedException("server does not accept ranges")
            }
            it.header("Content-Length")?.toLongOrNull()
        } ?: throw HlsPlaylist.UnsupportedException("unknown size")
        if (length < MIN_SPLIT_BYTES) throw HlsPlaylist.UnsupportedException("too small to split")

        val slices = (length / SLICE_BYTES).toInt().coerceIn(2, connections)
        val sliceSize = (length + slices - 1) / slices
        val written = AtomicLong()
        val gate = Semaphore(connections)

        val parts = coroutineScope {
            (0 until slices).map { index ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val start = index * sliceSize
                        val end = minOf(start + sliceSize, length) - 1
                        val part = File(workDir, "part_$index")
                        fetchSlice(url, headers, start, end, part) { chunk ->
                            val total = written.addAndGet(chunk)
                            onProgress(Progress(total.toDouble() / length, total, length))
                        }
                        part
                    }
                }
            }.awaitAll()
        }

        val merged = File(workDir, "video.bin")
        withContext(Dispatchers.IO) {
            merged.outputStream().buffered().use { out ->
                parts.forEach { part ->
                    part.inputStream().use { it.copyTo(out) }
                    part.delete() // keeps the disk use from doubling
                }
            }
        }
        if (merged.length() != length) throw IOException("size mismatch: ${merged.length()} of $length")
        return merged
    }

    private suspend fun fetchText(url: String, headers: Headers): String {
        client.newCall(Request.Builder().url(url).headers(headers).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string().also {
                if (!it.startsWith("#EXTM3U")) throw HlsPlaylist.UnsupportedException("not a playlist")
            }
        }
    }

    /** @return the size of the file, whether it was already there or just downloaded */
    private suspend fun fetchToFile(url: String, headers: Headers, destination: File): Long {
        if (destination.isFile && destination.length() > 0) return destination.length() // resumed run

        val part = File(destination.parentFile, destination.name + ".part")
        retrying {
            client.newCall(Request.Builder().url(url).headers(headers).build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.byteStream().use { input ->
                    part.outputStream().buffered().use { out -> copyCancellable(input, out) {} }
                }
            }
        }
        if (!part.renameTo(destination)) throw IOException("cannot move ${part.name}")
        return destination.length()
    }

    private suspend fun fetchSlice(
        url: String,
        headers: Headers,
        start: Long,
        end: Long,
        destination: File,
        onChunk: (Long) -> Unit,
    ) {
        val expected = end - start + 1
        retrying {
            destination.delete()
            val request = Request.Builder().url(url).headers(headers).header("Range", "bytes=$start-$end").build()
            client.newCall(request).await().use { response ->
                // 206 means the server honoured the range; a plain 200 would send the whole file.
                if (response.code != PARTIAL_CONTENT) throw IOException("range ignored (HTTP ${response.code})")
                response.body.byteStream().use { input ->
                    destination.outputStream().buffered().use { out -> copyCancellable(input, out, onChunk) }
                }
            }
            if (destination.length() != expected) throw IOException("short slice: ${destination.length()} of $expected")
        }
    }

    private suspend fun copyCancellable(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        onChunk: (Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            onChunk(read.toLong())
        }
    }

    private suspend fun retrying(block: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= RETRIES) throw e
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    companion object {
        const val LOCAL_PLAYLIST = "local.m3u8"
        const val DEFAULT_CONNECTIONS = 8

        private const val RETRIES = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val BUFFER_BYTES = 64 * 1024
        private const val MIN_SEGMENTS_FOR_ESTIMATE = 3
        private const val MIN_SPLIT_BYTES = 8L * 1024 * 1024
        private const val SLICE_BYTES = 4L * 1024 * 1024
        private const val PARTIAL_CONTENT = 206
    }
}
