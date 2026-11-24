package eu.kanade.presentation.entries.anime

import eu.kanade.tachiyomi.animesource.model.Video
import java.util.Locale

private const val MIB = 1024.0 * 1024.0
private const val GIB = MIB * 1024.0

/** "≈ 340 Mo", "≈ 1,2 Go" */
internal fun formatEstimatedSize(bytes: Long): String {
    val text = if (bytes >= GIB) {
        String.format(Locale.getDefault(), "%.1f Go", bytes / GIB)
    } else {
        String.format(Locale.getDefault(), "%d Mo", (bytes / MIB).toLong().coerceAtLeast(1))
    }
    return "≈ $text"
}

/**
 * What the user needs to choose a video besides its title: estimated size, subtitle and
 * audio languages. Null when the source gives nothing more than the title.
 */
internal fun videoInfoLabel(video: Video, sizeBytes: Long?): String? {
    val parts = buildList {
        sizeBytes?.let { add(formatEstimatedSize(it)) }
        video.subtitleTracks.map { it.lang }.filter { it.isNotBlank() }.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { add("ST " + it.joinToString(", ")) }
        video.audioTracks.map { it.lang }.filter { it.isNotBlank() }.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { add("Audio " + it.joinToString(", ")) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
