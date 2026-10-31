package eu.kanade.tachiyomi.data.telegram

import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser

/**
 * Détecte les fichiers qui ressemblent à des captures/exports de téléphone
 * (`VID_2026...`, `YouCut_...`) plutôt qu'à de vrais épisodes. Ces fichiers
 * sont fréquemment des pubs ou des enregistrements d'écran sans rapport avec
 * le titre annoncé dans la légende.
 */
object TelegramFileHeuristics {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "m2ts")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")

    private val GENERIC_RECORDING = Regex(
        """(?i)^(?:vid|youcut|img|pxl|screen[\s_-]?rec|whatsapp|telegram|trim|export)""" +
            """[\s_-]*\d.*\.(?:mp4|mkv|mov|avi|webm|m4v|ts)$""",
    )

    private val GENERIC_TITLE = Regex(
        """^(?:vid|youcut|img|pxl|screen|whatsapp|telegram|trim|export)-?\d.*$""",
    )
    private val GENERIC_VIDEO_TITLE = Regex(
        """^generic-video(?:-expires)?(?:-\d.*)?$""",
    )

    private val DOC_EXTENSIONS = setOf("cbz", "zip", "pdf", "epub", "rar", "7z", "cbr")
    private val UNSUPPORTED_EXTENSIONS = setOf(
        "apk", "xapk", "apks", "exe", "msi", "bat", "cmd", "sh", "jar", "vbs", "scr",
        "com", "bin", "iso", "dmg", "pkg", "deb", "rpm", "msix", "appx", "torrent",
        "url", "html", "htm", "php", "js",
    )
    private val DOC_MIME_TYPES = setOf(
        "application/pdf",
        "application/zip",
        "application/x-cbz",
        "application/vnd.comicbook+zip",
        "application/epub+zip",
    )

    fun isVideoFile(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return mime.startsWith("video/") || extension in VIDEO_EXTENSIONS
    }

    fun isImageFile(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return mime.startsWith("image/") || extension in IMAGE_EXTENSIONS
    }

    fun isComicDocument(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return isImageFile(fileName, mimeType) ||
            extension in DOC_EXTENSIONS ||
            mime in DOC_MIME_TYPES
    }

    /** APK, exécutables et fichiers publicitaires : jamais des œuvres. */
    fun isUnsupportedFile(fileName: String, mimeType: String?): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in UNSUPPORTED_EXTENSIONS ||
            mime == "application/vnd.android.package-archive" ||
            mime == "application/x-msdownload" ||
            mime == "application/x-msi" ||
            mime == "application/x-bittorrent" ||
            mime == "application/x-sh"
    }

    /** Vrai si le fichier est un export générique sans le titre de l'œuvre. */
    fun isSuspect(fileName: String, title: String): Boolean = isSuspect(fileName, null, title)

    /**
     * La légende Telegram porte souvent le vrai titre quand le fichier s'appelle
     * `VID_...` ou `YouCut_...`. Elle doit donc servir de preuve, sinon les
     * épisodes JoJo Steel Ball Run sont tous rejetés.
     */
    fun isSuspect(fileName: String, caption: String?, title: String): Boolean {
        val name = fileName.trim()
        val titleKey = TelegramTitleParser.slug(TelegramTitleParser.canonicalTitle(title))

        // Le titre lui-même est un nom d'export (VID 2026..., YouCut...).
        if (titleKey.isNotBlank() && (GENERIC_TITLE.matches(titleKey) || GENERIC_VIDEO_TITLE.matches(titleKey))) {
            return true
        }

        if (!GENERIC_RECORDING.matches(name)) return false
        if (titleKey.isBlank()) return true

        val captionKey = TelegramTitleParser.slug(TelegramTitleParser.canonicalTitle(caption.orEmpty()))
        return !captionKey.contains(titleKey) && !TelegramTitleParser.slug(name).contains(titleKey)
    }

    /** Vrai si le titre lui-même est un nom de fichier d'export. */
    fun isGenericTitle(title: String): Boolean {
        val key = TelegramTitleParser.slug(title)
        return GENERIC_TITLE.matches(key) || GENERIC_VIDEO_TITLE.matches(key)
    }
}
