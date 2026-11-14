package eu.kanade.tachiyomi.ui.social

fun formatTimecode(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = ((totalSeconds / 60) % 60).toString().padStart(2, '0')
    return if (totalSeconds >= 3600) {
        "${totalSeconds / 3600}:$minutes:$seconds"
    } else {
        "${(totalSeconds / 60).toString().padStart(2, '0')}:$seconds"
    }
}

fun loopWindow(
    anchorMs: Long,
    durationMs: Long,
    beforeMs: Long = 15_000L,
    afterMs: Long = 15_000L,
): LongRange {
    if (durationMs <= 0L) return anchorMs..anchorMs
    if (durationMs < 30_000L || durationMs < beforeMs + afterMs) return 0L..durationMs

    val start = (anchorMs - beforeMs).coerceIn(0L, durationMs)
    val end = (anchorMs + afterMs).coerceIn(start, durationMs)
    return start..end
}
