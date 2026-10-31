package eu.kanade.presentation.library.components

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.arthenica.ffmpegkit.FFmpegKit
import eu.kanade.tachiyomi.data.multisource.MultiSourceDebugLog
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

private const val MAX_GIF_SECONDS = 15f
private const val MIN_GIF_SECONDS = 1f

@Composable
fun CollectionEditDialog(
    initialBannerModel: Any?,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (bannerUri: Uri?, removeBanner: Boolean, description: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var removeBanner by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf(initialDescription) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var showGifTrim by remember { mutableStateOf(false) }
    var gifBusy by remember { mutableStateOf(false) }
    var gifError by remember { mutableStateOf<String?>(null) }
    var videoDuration by remember { mutableFloatStateOf(0f) }
    var gifStart by remember { mutableFloatStateOf(0f) }
    var gifEnd by remember { mutableFloatStateOf(MAX_GIF_SECONDS) }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            removeBanner = false
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            videoUri = uri
            gifError = null
            showGifTrim = true
        }
    }

    LaunchedEffect(showGifTrim, videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        if (!showGifTrim) return@LaunchedEffect
        val durationMs = withContext(Dispatchers.IO) {
            runCatching { readVideoDuration(context, uri) }.getOrDefault(0L)
        }
        val durationSec = (durationMs / 1000f).takeIf { it > 0f } ?: MAX_GIF_SECONDS
        videoDuration = durationSec
        gifStart = 0f
        gifEnd = min(MAX_GIF_SECONDS, durationSec)
    }

    val previewModel: Any? = when {
        removeBanner -> null
        pickedUri != null -> pickedUri
        else -> initialBannerModel
    }
    val hasBanner = previewModel != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Modifier la collection") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewModel != null) {
                        AsyncImage(
                            model = previewModel,
                            contentDescription = "Aperçu de la bannière",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                    } else {
                        DefaultEntryArtwork(modifier = Modifier.size(72.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { imageLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "Choisir une bannière (GIF ou Image)",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    if (hasBanner) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Bannière sélectionnée",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                TextButton(
                    onClick = { videoLauncher.launch(arrayOf("video/*")) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Créer un GIF depuis une vidéo")
                }

                if (hasBanner) {
                    TextButton(
                        onClick = {
                            pickedUri = null
                            removeBanner = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Supprimer la bannière")
                    }
                }

                gifError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(pickedUri, removeBanner, description) },
                enabled = !gifBusy,
            ) {
                Text("Sauvegarder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )

    if (showGifTrim && videoUri != null) {
        GifTrimDialog(
            duration = videoDuration,
            start = gifStart,
            end = gifEnd,
            busy = gifBusy,
            error = gifError,
            onStartChange = { value ->
                gifStart = value.coerceIn(0f, max(0f, gifEnd - MIN_GIF_SECONDS))
                if (gifEnd - gifStart > MAX_GIF_SECONDS) {
                    gifEnd = gifStart + MAX_GIF_SECONDS
                }
            },
            onEndChange = { value ->
                gifEnd = value.coerceIn(
                    gifStart + MIN_GIF_SECONDS,
                    max(gifStart + MIN_GIF_SECONDS, videoDuration),
                )
                if (gifEnd - gifStart > MAX_GIF_SECONDS) {
                    gifStart = gifEnd - MAX_GIF_SECONDS
                }
            },
            onDismiss = {
                if (!gifBusy) {
                    showGifTrim = false
                    gifError = null
                }
            },
            onCreate = {
                val clipDuration = (gifEnd - gifStart)
                    .coerceIn(MIN_GIF_SECONDS, MAX_GIF_SECONDS)
                if (!gifBusy && clipDuration > 0f) {
                    scope.launch {
                        gifBusy = true
                        gifError = null
                        val output = File(
                            context.cacheDir,
                            "collection_banner_${System.currentTimeMillis()}.gif",
                        )
                        val success = runCatching {
                            createGif(context, videoUri!!, gifStart, clipDuration, output)
                        }.getOrDefault(false)
                        if (success && output.exists()) {
                            val uri = runCatching {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    output,
                                )
                            }.getOrNull()
                            if (uri != null) {
                                pickedUri = uri
                                removeBanner = false
                                showGifTrim = false
                            } else {
                                gifError = "Impossible de préparer le GIF."
                            }
                        } else {
                            gifError = "Échec de la création du GIF."
                        }
                        gifBusy = false
                    }
                }
            },
        )
    }
}

@Composable
private fun GifTrimDialog(
    duration: Float,
    start: Float,
    end: Float,
    busy: Boolean,
    error: String?,
    onStartChange: (Float) -> Unit,
    onEndChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Créer un GIF") },
        text = {
            Column {
                Text(
                    text = "Durée de la vidéo : ${formatSeconds(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Début : ${formatSeconds(start)}")
                Slider(
                    value = start,
                    onValueChange = onStartChange,
                    valueRange = 0f..max(0f, duration),
                    enabled = !busy,
                )
                Text("Fin : ${formatSeconds(end)}")
                Slider(
                    value = end,
                    onValueChange = onEndChange,
                    valueRange = 0f..max(MIN_GIF_SECONDS, duration),
                    enabled = !busy,
                )
                Text(
                    text = "Extrait : ${formatSeconds(end - start)} (max ${MAX_GIF_SECONDS.toInt()} s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (busy) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Conversion en cours…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = !busy) {
                Text("Créer le GIF")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Annuler")
            }
        },
    )
}

private fun readVideoDuration(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
    } finally {
        retriever.release()
    }
}

private suspend fun createGif(
    context: Context,
    videoUri: Uri,
    startSec: Float,
    durationSec: Float,
    output: File,
): Boolean = withContext(Dispatchers.IO) {
    val input = videoUri.toFFmpegString(context)
    val outputPath = output.absolutePath.replace("\\", "/")
    val filter = "fps=10,scale=480:-1:flags=lanczos," +
        "split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer"
    val command = buildString {
        append("-ss ")
        append(String.format(Locale.US, "%.3f", startSec))
        append(" -t ")
        append(String.format(Locale.US, "%.3f", durationSec))
        append(" -i \"")
        append(input)
        append("\" -vf \"")
        append(filter)
        append("\" -loop 0 -y \"")
        append(outputPath)
        append("\"")
    }

    suspendCancellableCoroutine { continuation ->
        val session = FFmpegKit.executeAsync(command) { finished ->
            val success = finished.returnCode?.isValueSuccess == true &&
                output.exists() &&
                output.length() > 0L
            if (success) {
                MultiSourceDebugLog.log(
                    "gif",
                    "created path=${output.name} size=${output.length()} start=${startSec}s duration=${durationSec}s",
                )
            } else {
                MultiSourceDebugLog.log(
                    "gif",
                    "failed rc=${finished.returnCode} start=${startSec}s duration=${durationSec}s " +
                        "output=${finished.output?.takeLast(2_000).orEmpty()}",
                )
            }
            if (continuation.isActive) {
                continuation.resume(success)
            }
        }
        continuation.invokeOnCancellation {
            runCatching { FFmpegKit.cancel(session.sessionId) }
        }
    }
}

private fun formatSeconds(seconds: Float): String {
    val total = seconds.coerceAtLeast(0f).toInt()
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}
