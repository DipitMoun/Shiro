package eu.kanade.tachiyomi.ui.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** An image the user picked, ready to be sent. */
class PickedImage(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

/** Reads a picked image and shrinks big photos so they do not eat the mobile data of both sides. */
object ChatImagePreparer {

    private const val MAX_SOURCE_BYTES = 30 * 1024 * 1024
    private const val KEEP_AS_IS_BYTES = 1_500_000
    private const val MAX_SIDE = 1920
    private const val JPEG_QUALITY = 85

    suspend fun prepare(context: Context, uri: Uri): PickedImage? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val original = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        if (original.isEmpty() || original.size > MAX_SOURCE_BYTES) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return@withContext null

        val extension = when {
            mime.equals("image/gif", true) -> "gif"
            mime.equals("image/png", true) -> "png"
            mime.equals("image/webp", true) -> "webp"
            else -> "jpg"
        }
        val name = "shiro-${System.currentTimeMillis()}.$extension"

        // GIFs are sent untouched (re-encoding would drop the animation); small images too.
        val small = original.size <= KEEP_AS_IS_BYTES && maxOf(width, height) <= MAX_SIDE
        if (mime.equals("image/gif", true) || small) {
            return@withContext PickedImage(original, name, mime, width, height)
        }

        val sample = generateSequence(1) { it * 2 }
            .first { maxOf(width, height) / it <= MAX_SIDE * 2 }
        val decoded = BitmapFactory.decodeByteArray(
            original,
            0,
            original.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val result = PickedImage(out.toByteArray(), "shiro-${System.currentTimeMillis()}.jpg", "image/jpeg", scaled.width, scaled.height)
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        result
    }
}
