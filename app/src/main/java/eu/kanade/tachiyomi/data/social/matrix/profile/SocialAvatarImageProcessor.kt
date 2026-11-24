package eu.kanade.tachiyomi.data.social.matrix.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreparedSocialAvatar(
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Normalizes a picked image before the Matrix upload: center-crops it to a
 * square, caps its size and recompresses it as JPEG. This avoids sending a
 * multi-megapixel original and keeps the upload well under common homeserver
 * limits.
 */
object SocialAvatarImageProcessor {

    private const val MAX_DIMENSION = 512
    private const val JPEG_QUALITY = 88

    suspend fun prepare(context: Context, uri: Uri): SocialResult<PreparedSocialAvatar> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsStream = resolver.openInputStream(uri)
                    ?: return@withContext failure("Cannot open the selected image")
                boundsStream.use { stream ->
                    // Bounds-only decoding intentionally returns null; the
                    // dimensions are written into [bounds].
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext failure("Cannot read the selected image")
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(
                        width = bounds.outWidth,
                        height = bounds.outHeight,
                        maxDimension = MAX_DIMENSION,
                    )
                }
                val decoded = resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return@withContext failure("Cannot decode the selected image")

                var current = decoded
                try {
                    val oriented = applyExifOrientation(current, readExifOrientation(context, uri))
                    if (oriented !== current) {
                        current.recycle()
                        current = oriented
                    }

                    val cropped = centerCropSquare(current)
                    if (cropped !== current) {
                        current.recycle()
                        current = cropped
                    }

                    val scaled = if (current.width > MAX_DIMENSION) {
                        Bitmap.createScaledBitmap(current, MAX_DIMENSION, MAX_DIMENSION, true)
                    } else {
                        current
                    }
                    if (scaled !== current) {
                        current.recycle()
                        current = scaled
                    }

                    val opaque = flattenOnWhite(current)
                    if (opaque !== current) {
                        current.recycle()
                        current = opaque
                    }

                    val output = ByteArrayOutputStream()
                    val compressed = current.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                    if (!compressed) {
                        return@withContext failure("Cannot encode the selected image")
                    }
                    SocialResult.Success(
                        PreparedSocialAvatar(
                            mimeType = "image/jpeg",
                            bytes = output.toByteArray(),
                        ),
                    )
                } finally {
                    current.recycle()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                SocialResult.Failure(
                    SocialError.Unknown("Avatar image processing failed: ${t.message}", t),
                )
            }
        }

    @Suppress("DEPRECATION")
    private fun readExifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Throwable) {
        // EXIF is optional; malformed metadata must not block the upload.
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (_: IllegalArgumentException) {
            source
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension * 2 || height / sample > maxDimension * 2) {
            sample *= 2
        }
        return sample
    }

    private fun centerCropSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return flattened
    }

    private fun failure(message: String): SocialResult<PreparedSocialAvatar> =
        SocialResult.Failure(SocialError.Unknown(message))
}
