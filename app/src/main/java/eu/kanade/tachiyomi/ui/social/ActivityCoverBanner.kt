package eu.kanade.tachiyomi.ui.social

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The wide picture of an activity notification. Notifications show a 2:1 picture, and a poster is
 * 2:3: cropped to fit it would lose most of itself. So the poster is drawn whole, on the left, over
 * a darkened and blurred copy of itself that fills the rest.
 */
object ActivityCoverBanner {

    const val WIDTH = 1024
    const val HEIGHT = 512
    private const val MARGIN = 28
    private const val MAX_DOWNLOAD_BYTES = 6L * 1024 * 1024
    private const val MAX_DECODED_SIDE = 1400
    private const val POSTER_ICON_WIDTH = 128

    /** Size of the poster in the banner: as tall as the margins allow, never wider than [maxWidth]. */
    internal fun posterSize(srcWidth: Int, srcHeight: Int, bannerHeight: Int, margin: Int, maxWidth: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 0 to 0
        var height = bannerHeight - 2 * margin
        var width = (height.toLong() * srcWidth / srcHeight).toInt()
        if (width > maxWidth) {
            width = maxWidth
            height = (width.toLong() * srcHeight / srcWidth).toInt()
        }
        return width to height
    }

    /** The wide picture, and the poster alone for the collapsed notification. */
    class Rendered(val banner: Bitmap, val poster: Bitmap)

    /** Downloads the cover (https only: the address may come from a friend's message) and draws the banner. */
    suspend fun load(url: String?): Bitmap? = loadWithPoster(url)?.banner

    suspend fun loadWithPoster(url: String?): Rendered? {
        if (url == null || !url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = Injekt.get<NetworkHelper>().client
                val bytes = client.newCall(GET(url)).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body
                    if (body.contentLength() > MAX_DOWNLOAD_BYTES) return@use null
                    body.bytes().takeIf { it.size <= MAX_DOWNLOAD_BYTES }
                } ?: return@runCatching null
                decode(bytes)?.let { cover ->
                    val poster = Bitmap.createScaledBitmap(cover, POSTER_ICON_WIDTH, POSTER_ICON_WIDTH * 3 / 2, true)
                    Rendered(render(cover), poster).also { cover.recycle() }
                }
            }.getOrNull()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODED_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    internal fun render(cover: Bitmap): Bitmap {
        val banner = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(banner)

        // Background: the cover scaled to fill the banner, blurred by a round trip through a tiny size.
        val tinyWidth = 48
        val tinyHeight = (tinyWidth.toFloat() * HEIGHT / WIDTH).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createBitmap(tinyWidth, tinyHeight, Bitmap.Config.ARGB_8888)
        val fill = maxOf(tinyWidth.toFloat() / cover.width, tinyHeight.toFloat() / cover.height)
        Canvas(tiny).apply {
            val m = Matrix().apply {
                postScale(fill, fill)
                postTranslate((tinyWidth - cover.width * fill) / 2f, (tinyHeight - cover.height * fill) / 2f)
            }
            drawBitmap(cover, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        canvas.drawBitmap(Bitmap.createScaledBitmap(tiny, WIDTH, HEIGHT, true), 0f, 0f, null)
        tiny.recycle()
        canvas.drawColor(Color.argb(140, 0, 0, 0))

        // Poster, whole, with rounded corners.
        val (width, height) = posterSize(cover.width, cover.height, HEIGHT, MARGIN, WIDTH / 2)
        if (width > 0) {
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    Matrix().apply {
                        postScale(width.toFloat() / cover.width, height.toFloat() / cover.height)
                        postTranslate(MARGIN.toFloat(), ((HEIGHT - height) / 2).toFloat())
                    },
                )
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            val rect = RectF(MARGIN.toFloat(), ((HEIGHT - height) / 2).toFloat(), (MARGIN + width).toFloat(), ((HEIGHT + height) / 2).toFloat())
            canvas.drawRoundRect(rect, 22f, 22f, paint)
        }
        return banner
    }
}
