package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Charge un chapitre PDF téléchargé.
 *
 * Le lecteur manga ne gère nativement que les archives d'images. Chaque page
 * PDF est rendue à la demande avec [PdfRenderer] puis encodée en PNG.
 */
internal class PdfPageLoader(
    private val file: UniFile,
) : PageLoader() {

    private val context: Application by injectLazy()

    override var isLocal: Boolean = true

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    override suspend fun getPages(): List<ReaderPage> = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("Impossible d'ouvrir le PDF : ${file.name}")

        val pdf = PdfRenderer(pfd)
        descriptor = pfd
        renderer = pdf

        (0 until pdf.pageCount).map { index ->
            ReaderPage(index).apply {
                stream = { renderPage(index) }
                status = Page.State.READY
            }
        }
    }

    override fun recycle() {
        super.recycle()
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    private fun renderPage(index: Int): InputStream {
        val pdf = renderer ?: error("PDF déjà fermé")

        synchronized(pdf) {
            val page = pdf.openPage(index)
            try {
                val widthScale = MAX_WIDTH.toFloat() / page.width.coerceAtLeast(1)
                val heightScale = MAX_HEIGHT.toFloat() / page.height.coerceAtLeast(1)
                val scale = minOf(widthScale, heightScale)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    return ByteArrayInputStream(output.toByteArray())
                } finally {
                    bitmap.recycle()
                }
            } finally {
                page.close()
            }
        }
    }

    private companion object {
        const val MAX_WIDTH = 1600
        const val MAX_HEIGHT = 6000
    }
}
