package eu.kanade.tachiyomi.data.telegram.tdlib

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.drinkless.tdlib.TdApi
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Télécharge un fichier TDLib et le copie vers une destination.
 *
 * Le Flow est froid : chaque collecte démarre un téléchargement. Ne pas collecter
 * le même fichier plusieurs fois en parallèle.
 */
class TelegramDownloader(
    private val authManager: TelegramAuthManager,
) {

    fun download(
        metadata: TelegramFileMetadata,
        destination: File,
        priority: Int = DEFAULT_PRIORITY,
    ): Flow<TelegramDownloadState> {
        return download(
            fileId = metadata.fileId,
            expectedSize = metadata.sizeBytes,
            destination = destination,
            priority = priority,
        )
    }

    /**
     * TODO(UI) : collecter ce Flow dans un ViewModel/notifier et afficher la progression.
     * TODO(Phase 3) : déplacer le téléchargement dans un ForegroundService pour les gros fichiers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun download(
        fileId: Int,
        expectedSize: Long,
        destination: File,
        priority: Int = DEFAULT_PRIORITY,
    ): Flow<TelegramDownloadState> = channelFlow {
        if (priority !in 1..32) {
            send(TelegramDownloadState.Failed("Priorité TDLib invalide: $priority"))
            close()
            return@channelFlow
        }

        if (expectedSize > MAX_FILE_SIZE) {
            send(
                TelegramDownloadState.Failed(
                    "Fichier refusé : ${formatBytes(expectedSize)} > limite de 2 GiB.",
                ),
            )
            close()
            return@channelFlow
        }

        val client = try {
            authManager.requireClient()
        } catch (t: Throwable) {
            send(TelegramDownloadState.Failed(t.message ?: "TDLib n'est pas prêt.", t))
            close()
            return@channelFlow
        }

        val finished = AtomicBoolean(false)
        var updateJob: Job? = null

        send(TelegramDownloadState.Queued)

        try {
            // S'abonner avant DownloadFile pour ne pas rater la fin d'un petit fichier.
            updateJob = launch {
                authManager.updates
                    .filterIsInstance<TdApi.UpdateFile>()
                    .filter { it.file.id == fileId }
                    .collect { update ->
                        val file = update.file

                        if (file.local.isDownloadingCompleted) {
                            if (finished.compareAndSet(false, true)) {
                                completeDownload(file, destination)
                                close()
                            }
                        } else if (!finished.get()) {
                            runCatching {
                                send(progressState(file))
                            }
                        }
                    }
            }

            val initial = client.sendAwait<TdApi.File>(
                TdApi.DownloadFile(
                    fileId,
                    priority,
                    0,
                    0,
                    false,
                ),
            )

            if (finished.get()) {
                return@channelFlow
            }

            if (initial.local.isDownloadingCompleted) {
                if (finished.compareAndSet(false, true)) {
                    completeDownload(initial, destination)
                    close()
                }
                return@channelFlow
            }

            send(progressState(initial))
            awaitClose { }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "Téléchargement TDLib échoué pour fileId=$fileId" }
            if (finished.compareAndSet(false, true)) {
                runCatching {
                    send(
                        TelegramDownloadState.Failed(
                            t.message ?: "Téléchargement TDLib échoué.",
                            t,
                        ),
                    )
                }
            }
            close()
        } finally {
            updateJob?.cancel()

            if (!finished.get()) {
                runCatching {
                    client.send(TdApi.CancelDownloadFile(fileId, false)) { }
                }
            }
        }
    }

    private suspend fun ProducerScope<TelegramDownloadState>.completeDownload(
        file: TdApi.File,
        destination: File,
    ) {
        val sourcePath = file.local.path
        val source = sourcePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

        if (source == null || !source.isFile) {
            send(
                TelegramDownloadState.Failed(
                    "Fichier TDLib terminé mais introuvable sur le disque.",
                ),
            )
            return
        }

        val totalBytes = source.length()
        if (totalBytes > MAX_FILE_SIZE) {
            send(
                TelegramDownloadState.Failed(
                    "Fichier reçu trop volumineux : ${formatBytes(totalBytes)} > 2 GiB.",
                ),
            )
            return
        }

        send(TelegramDownloadState.Copying(0, totalBytes))

        try {
            val copied = copyToDestination(source, destination) { copiedBytes, total ->
                send(TelegramDownloadState.Copying(copiedBytes, total))
            }
            send(TelegramDownloadState.Completed(destination, copied))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "Copie TDLib -> destination échouée" }
            send(
                TelegramDownloadState.Failed(
                    t.message ?: "Copie du fichier TDLib échouée.",
                    t,
                ),
            )
        }
    }

    /**
     * Copie avec reprise via un fichier .part.
     *
     * La source est déjà complète dans le cache TDLib. Si un .part existe,
     * on reprend à sa taille ; cela couvre une coupure pendant la copie finale.
     */
    private suspend fun copyToDestination(
        source: File,
        destination: File,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val parent = destination.parentFile
            ?: throw IOException("Destination sans dossier parent: ${destination.absolutePath}")

        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Impossible de créer le dossier: ${parent.absolutePath}")
        }

        val totalBytes = source.length()

        if (destination.isFile && destination.length() == totalBytes) {
            return@withContext totalBytes
        }

        val partFile = File(parent, "${destination.name}.part")

        if (partFile.isFile && partFile.length() > totalBytes) {
            partFile.delete()
        }

        val startOffset = if (partFile.isFile) {
            partFile.length().coerceAtMost(totalBytes)
        } else {
            0L
        }

        if (startOffset < totalBytes) {
            val requiredBytes = totalBytes - startOffset
            val usableBytes = parent.usableSpace
            if (usableBytes in 1 until requiredBytes) {
                throw IOException(
                    "Espace insuffisant : ${formatBytes(requiredBytes)} requis, " +
                        "${formatBytes(usableBytes)} disponibles.",
                )
            }
        }

        if (startOffset == totalBytes) {
            replacePart(partFile, destination)
            return@withContext totalBytes
        }

        source.inputStream().use { input ->
            skipFully(input, startOffset)

            RandomAccessFile(partFile, "rw").use { output ->
                output.seek(startOffset)

                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copiedBytes = startOffset
                var lastReportedBytes = startOffset

                while (true) {
                    currentCoroutineContext().ensureActive()

                    val read = input.read(buffer)
                    if (read < 0) break

                    output.write(buffer, 0, read)
                    copiedBytes += read

                    if (
                        copiedBytes - lastReportedBytes >= PROGRESS_STEP_BYTES ||
                        copiedBytes == totalBytes
                    ) {
                        onProgress(copiedBytes, totalBytes)
                        lastReportedBytes = copiedBytes
                    }
                }
            }
        }

        replacePart(partFile, destination)
        destination.length()
    }

    private fun replacePart(partFile: File, destination: File) {
        if (partFile.renameTo(destination)) {
            return
        }

        partFile.copyTo(destination, overwrite = true)
        partFile.delete()
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(COPY_BUFFER_SIZE)

        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }

            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            if (read < 0) return
            remaining -= read
        }
    }

    private fun progressState(file: TdApi.File): TelegramDownloadState.Downloading {
        val totalBytes = maxOf(file.size, file.expectedSize, 1L)
        val downloadedBytes = (
            file.local.downloadOffset +
                file.local.downloadedPrefixSize
            ).coerceIn(0L, totalBytes)

        return TelegramDownloadState.Downloading(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = arrayOf("Ko", "Mo", "Go", "To")
        var value = bytes.toDouble()
        var unitIndex = -1

        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }

        return "%.1f %s".format(value, units[unitIndex])
    }

    private companion object {
        const val DEFAULT_PRIORITY = 16
        const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024
        const val COPY_BUFFER_SIZE = 1 shl 20
        const val PROGRESS_STEP_BYTES = 1 shl 20
    }
}

sealed interface TelegramDownloadState {
    data object Queued : TelegramDownloadState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : TelegramDownloadState {
        val fraction: Float
            get() = if (totalBytes <= 0L) {
                0f
            } else {
                (downloadedBytes.toDouble() / totalBytes)
                    .toFloat()
                    .coerceIn(0f, 1f)
            }
    }

    data class Copying(
        val copiedBytes: Long,
        val totalBytes: Long,
    ) : TelegramDownloadState {
        val fraction: Float
            get() = if (totalBytes <= 0L) {
                0f
            } else {
                (copiedBytes.toDouble() / totalBytes)
                    .toFloat()
                    .coerceIn(0f, 1f)
            }
    }

    data class Completed(
        val destination: File,
        val sizeBytes: Long,
    ) : TelegramDownloadState

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : TelegramDownloadState
}
