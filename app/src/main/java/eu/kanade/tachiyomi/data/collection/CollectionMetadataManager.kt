package eu.kanade.tachiyomi.data.collection

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Library a collection belongs to.
 *
 * Anime and manga categories live in two separate tables, so their ids overlap (both have a
 * category 1). Every metadata lookup must therefore be namespaced by this type.
 */
enum class CollectionType(val key: String) {
    ANIME("anime"),
    MANGA("manga"),
    ;

    companion object {
        fun fromKey(key: String?): CollectionType = entries.firstOrNull { it.key == key } ?: ANIME
    }
}

@Serializable
data class CollectionMetadata(
    val categoryId: Long,
    /** [CollectionType.key]; defaults to anime for files written before the split. */
    val type: String = CollectionType.ANIME.key,
    val bannerUrl: String? = null,
    val bannerPath: String? = null,
    val description: String = "",
    val lastOpenedAt: Long = 0L,
) {
    val collectionType: CollectionType
        get() = CollectionType.fromKey(type)
}

class CollectionMetadataManager(private val context: Context) {
    private val file = File(context.filesDir, "collection_metadata.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private var cache: MutableMap<String, CollectionMetadata> = mutableMapOf()

    init {
        load()
    }

    private fun cacheKey(type: CollectionType, categoryId: Long): String = "${type.key}:$categoryId"

    private fun load() {
        if (file.exists()) {
            runCatching {
                val content = file.readText()
                val list = json.decodeFromString<List<CollectionMetadata>>(content)
                cache = list.associateBy { cacheKey(it.collectionType, it.categoryId) }.toMutableMap()
            }
        }
    }

    private fun save() {
        runCatching {
            val content = json.encodeToString(cache.values.toList())
            file.writeText(content)
        }
    }

    fun getMetadata(type: CollectionType, categoryId: Long): CollectionMetadata {
        return cache[cacheKey(type, categoryId)] ?: CollectionMetadata(categoryId = categoryId, type = type.key)
    }

    fun updateMetadata(
        type: CollectionType,
        categoryId: Long,
        bannerUrl: String?,
        bannerPath: String?,
        description: String,
    ) {
        val existing = getMetadata(type, categoryId)
        cache[cacheKey(type, categoryId)] = existing.copy(
            bannerUrl = bannerUrl,
            bannerPath = bannerPath,
            description = description,
        )
        save()
    }

    /**
     * Installe la bannière Telegram par défaut sans écraser un choix utilisateur.
     *
     * Le fichier est copié dans le dossier des bannières : Coil peut ainsi le charger
     * comme n'importe quelle autre bannière locale, y compris après une mise à jour.
     */
    fun ensureTelegramBanner(type: CollectionType, categoryId: Long) {
        if (categoryId <= 0L) return

        val existing = getMetadata(type, categoryId)
        val hasCustomBanner = (!existing.bannerPath.isNullOrBlank() && File(existing.bannerPath).isFile) ||
            !existing.bannerUrl.isNullOrBlank()
        if (hasCustomBanner) return

        val bannerPath = runCatching {
            val dir = File(context.filesDir, "banners").apply { mkdirs() }
            val target = File(dir, TELEGRAM_BANNER_FILE)
            if (!target.isFile || target.length() == 0L) {
                val temp = File(dir, "$TELEGRAM_BANNER_FILE.tmp")
                context.resources.openRawResource(R.drawable.telegram_baniere).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            target.absolutePath
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Bannière Telegram par défaut indisponible" }
            return
        }

        updateMetadata(
            type = type,
            categoryId = categoryId,
            bannerUrl = null,
            bannerPath = bannerPath,
            description = existing.description,
        )
    }

    fun saveBannerFile(type: CollectionType, categoryId: Long, sourceUri: Uri): String? {
        return runCatching {
            val dir = File(context.filesDir, "banners").apply { mkdirs() }
            val mime = context.contentResolver.getType(sourceUri)
            val ext = when {
                mime == "image/jpeg" -> "jpg"
                mime != null -> mime.substringAfterLast('/')
                else ->
                    sourceUri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() }
                        ?: "jpg"
            }
            val target = File(dir, "${type.key}_$categoryId.$ext")
            val temp = File(dir, "${target.name}.tmp")

            val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) {
                temp.delete()
                return@runCatching null
            }

            // On ne supprime l'ancienne bannière qu'une fois la nouvelle copiée.
            dir.listFiles { file ->
                file.name.startsWith("${type.key}_$categoryId.") && file != temp
            }?.forEach { it.delete() }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.absolutePath
        }.getOrNull()
    }

    fun markOpened(type: CollectionType, categoryId: Long) {
        if (categoryId <= 0L) return
        val existing = getMetadata(type, categoryId)
        cache[cacheKey(type, categoryId)] = existing.copy(lastOpenedAt = System.currentTimeMillis())
        save()
    }

    /**
     * Recently opened collections of [type] only, so an anime collection can never surface in the
     * manga library (or the other way around) when their ids collide.
     */
    fun getRecentCollections(type: CollectionType, limit: Int = 3): List<CollectionMetadata> {
        return cache.values
            .filter { it.collectionType == type && it.lastOpenedAt > 0L }
            .sortedByDescending { it.lastOpenedAt }
            .take(limit)
    }

    private companion object {
        const val TELEGRAM_BANNER_FILE = "telegram_default.jpg"
    }
}
