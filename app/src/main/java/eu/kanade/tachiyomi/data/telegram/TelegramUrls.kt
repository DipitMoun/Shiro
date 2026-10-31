package eu.kanade.tachiyomi.data.telegram

/**
 * URIs internes de la source Telegram.
 *
 * Elles ne sont jamais ouvertes par le réseau : elles identifient une œuvre, un
 * épisode ou un fichier TDLib que l'application résout elle-même.
 */
object TelegramUrls {
    const val SCHEME = "telegram://"

    fun isTelegram(url: String?): Boolean = url?.startsWith(SCHEME) == true

    // ------------------------------------------------------------------
    // Œuvre
    // ------------------------------------------------------------------

    fun work(chatId: Long, slug: String, season: Int?): String =
        "$SCHEME" + "anime/$chatId/$slug/${season ?: 0}"

    fun manga(chatId: Long, slug: String): String = "$SCHEME" + "manga/$chatId/$slug"

    // Œuvre agrégée sur plusieurs canaux (même titre/saison).
    fun aggregatedWork(workKey: String): String =
        "$SCHEME" + "aggregated/work/${encode(workKey)}"

    fun aggregatedEpisode(workKey: String, season: Int?, number: Int): String =
        "$SCHEME" + "aggregated/episode/${encode(workKey)}/${season ?: 0}/$number"

    data class AggregatedEpisodeRef(val workKey: String, val season: Int?, val number: Int)

    fun parseAggregatedWork(url: String?): String? {
        if (url == null || !isTelegram(url)) return null
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 3 || parts[0] != "aggregated" || parts[1] != "work") return null
        return runCatching { decode(parts[2]) }.getOrNull()
    }

    fun parseAggregatedEpisode(url: String?): AggregatedEpisodeRef? {
        if (url == null || !isTelegram(url)) return null
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 5 || parts[0] != "aggregated" || parts[1] != "episode") return null
        val workKey = runCatching { decode(parts[2]) }.getOrNull() ?: return null
        val season = parts[3].toIntOrNull()?.takeIf { it > 0 }
        val number = parts[4].toIntOrNull() ?: return null
        return AggregatedEpisodeRef(workKey, season, number)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

    data class WorkRef(val chatId: Long, val slug: String, val season: Int?)

    fun parseWork(url: String): WorkRef? {
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 4 || parts[0] != "anime") return null
        val chatId = parts[1].toLongOrNull() ?: return null
        val season = parts[3].toIntOrNull()?.takeIf { it > 0 }
        return WorkRef(chatId, parts[2], season)
    }

    data class MangaRef(val chatId: Long, val slug: String)

    fun parseManga(url: String): MangaRef? {
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 3 || parts[0] != "manga") return null
        val chatId = parts[1].toLongOrNull() ?: return null
        return MangaRef(chatId, parts[2])
    }

    // ------------------------------------------------------------------
    // Épisode
    // ------------------------------------------------------------------

    fun episode(chatId: Long, slug: String, season: Int?, number: Int): String =
        "$SCHEME" + "episode/$chatId/$slug/${season ?: 0}/$number"

    data class EpisodeRef(val chatId: Long, val slug: String, val season: Int?, val number: Int)

    fun parseEpisode(url: String): EpisodeRef? {
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 5 || parts[0] != "episode") return null
        val chatId = parts[1].toLongOrNull() ?: return null
        val season = parts[3].toIntOrNull()?.takeIf { it > 0 }
        val number = parts[4].toIntOrNull() ?: return null
        return EpisodeRef(chatId, parts[2], season, number)
    }

    // ------------------------------------------------------------------
    // Œuvre validée manuellement
    // ------------------------------------------------------------------

    fun savedAnime(workId: String): String = "$SCHEME" + "saved/anime/$workId"

    fun savedManga(workId: String): String = "$SCHEME" + "saved/manga/$workId"

    data class SavedRef(val id: String, val manga: Boolean)

    fun parseSaved(url: String?): SavedRef? {
        if (url == null || !isTelegram(url)) return null
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 3 || parts[0] != "saved") return null
        val manga = when (parts[1]) {
            "anime" -> false
            "manga" -> true
            else -> return null
        }
        return SavedRef(parts[2], manga)
    }

    /** Unité d'une œuvre validée : un épisode (vidéo) ou un chapitre (document). */
    fun savedUnit(workId: String, season: Int?, number: Int?): String =
        "$SCHEME" + "saved/unit/$workId/${season ?: 0}/${number ?: 0}"

    data class SavedUnitRef(val id: String, val season: Int?, val number: Int?)

    fun parseSavedUnit(url: String?): SavedUnitRef? {
        if (url == null || !isTelegram(url)) return null
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 5 || parts[0] != "saved" || parts[1] != "unit") return null
        val season = parts[3].toIntOrNull()?.takeIf { it > 0 }
        val number = parts[4].toIntOrNull()?.takeIf { it > 0 }
        return SavedUnitRef(parts[2], season, number)
    }

    /** Fichier isolé d'une œuvre validée (sans numéro d'épisode). */
    fun savedFile(workId: String, messageId: Long): String =
        "$SCHEME" + "saved/file/$workId/$messageId"

    data class SavedFileRef(val id: String, val messageId: Long)

    fun parseSavedFile(url: String?): SavedFileRef? {
        if (url == null || !isTelegram(url)) return null
        val parts = url.removePrefix(SCHEME).split('/')
        if (parts.size != 4 || parts[0] != "saved" || parts[1] != "file") return null
        val messageId = parts[3].toLongOrNull() ?: return null
        return SavedFileRef(parts[2], messageId)
    }

    // ------------------------------------------------------------------
    // Fichier
    // ------------------------------------------------------------------

    fun file(
        fileId: Int,
        sizeBytes: Long,
        chatId: Long,
        extension: String? = null,
        messageId: Long = 0L,
    ): String {
        val ext = extension
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?.let { "&ext=$it" }
            .orEmpty()
        val msg = messageId.takeIf { it > 0L }?.let { "&msg=$it" }.orEmpty()
        return "$SCHEME" + "file/$fileId?size=$sizeBytes&chat=$chatId$ext$msg"
    }

    data class FileRef(
        val fileId: Int,
        val sizeBytes: Long,
        val chatId: Long?,
        val extension: String?,
        val messageId: Long = 0L,
    )

    fun parseFile(url: String?): FileRef? {
        if (url == null || !isTelegram(url)) return null
        val withoutScheme = url.removePrefix(SCHEME)
        if (!withoutScheme.startsWith("file/")) return null
        val idPart = withoutScheme.removePrefix("file/").substringBefore('?')
        val fileId = idPart.toIntOrNull() ?: return null
        val query = withoutScheme.substringAfter('?', missingDelimiterValue = "")
        val size = query.substringAfter("size=", missingDelimiterValue = "0")
            .substringBefore('&')
            .toLongOrNull() ?: 0L
        val chat = query.substringAfter("chat=", missingDelimiterValue = "")
            .substringBefore('&')
            .toLongOrNull()
        val ext = query.substringAfter("ext=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
        val messageId = query.substringAfter("msg=", missingDelimiterValue = "")
            .substringBefore('&')
            .toLongOrNull() ?: 0L
        return FileRef(fileId, size, chat, ext, messageId)
    }
}
