package eu.kanade.tachiyomi.data.multisource

import android.app.Application
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petit journal persistant pour diagnostiquer les résolutions multi-source.
 * Il est lisible via `run-as` en debug et reste local à l'application.
 */
object MultiSourceDebugLog {
    private const val MAX_SIZE_BYTES = 2L * 1024 * 1024

    private val application: Application by lazy { Injekt.get() }
    private val file: File by lazy { File(application.filesDir, "multisource-debug.log") }
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        runCatching {
            if (file.length() > MAX_SIZE_BYTES) file.delete()
            val suffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            file.appendText("${formatter.format(Date())} [$tag] $message$suffix\n")
        }
    }
}
