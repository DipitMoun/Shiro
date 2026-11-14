package eu.kanade.tachiyomi.data.media

/**
 * Règles communes de validation des épisodes.
 *
 * Un fichier de moins de 50 Mo est considéré comme un faux média : miniature,
 * GIF, publicité ou extrait. Les URLs qui pointent directement vers une image
 * sont également refusées avant même le téléchargement.
 */
object EpisodeMediaValidator {
    const val MIN_EPISODE_BYTES = 50L * 1024L * 1024L
    const val MIN_EPISODE_SECONDS = 180

    private val IMAGE_EXTENSIONS = setOf(
        "jpg",
        "jpeg",
        "png",
        "webp",
        "gif",
        "bmp",
        "avif",
        "heic",
        "svg",
    )

    fun isImageOrGif(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.startsWith("data:image/")) return true
        val extension = path.substringAfterLast('.', "")
        return extension in IMAGE_EXTENSIONS
    }

    fun isTooSmall(sizeBytes: Long?): Boolean {
        return sizeBytes != null && sizeBytes < MIN_EPISODE_BYTES
    }

    /**
     * Reject media whose duration is below 3 minutes when TDLib provides it.
     * Falls back to size heuristics for legacy entries that never had duration.
     * Duration is the only signal that catches high-bitrate 90 s videos
     * (openings, teasers) regardless of their file size.
     */
    fun isTooShort(durationSeconds: Int?, sizeBytes: Long?): Boolean {
        if (durationSeconds != null && durationSeconds > 0) {
            return durationSeconds < MIN_EPISODE_SECONDS
        }
        return isTooSmall(sizeBytes)
    }

    fun isAcceptableVideoUrl(url: String): Boolean {
        return url.isNotBlank() && !isImageOrGif(url)
    }
}
