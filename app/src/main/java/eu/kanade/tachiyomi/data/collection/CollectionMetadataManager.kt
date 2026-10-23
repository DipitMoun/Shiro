package eu.kanade.tachiyomi.data.collection

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CollectionMetadata(
    val categoryId: Long,
    val bannerUrl: String? = null,
    val bannerPath: String? = null,
    val description: String = "",
    val lastOpenedAt: Long = 0L,
)

class CollectionMetadataManager(private val context: Context) {
    private val file = File(context.filesDir, "collection_metadata.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private var cache: MutableMap<Long, CollectionMetadata> = mutableMapOf()

    init {
        load()
    }

    private fun load() {
        if (file.exists()) {
            runCatching {
                val content = file.readText()
                val list = json.decodeFromString<List<CollectionMetadata>>(content)
                cache = list.associateBy { it.categoryId }.toMutableMap()
            }
        }
    }

    private fun save() {
        runCatching {
            val content = json.encodeToString(cache.values.toList())
            file.writeText(content)
        }
    }

    fun getMetadata(categoryId: Long): CollectionMetadata {
        return cache[categoryId] ?: CollectionMetadata(categoryId)
    }

    fun updateMetadata(
        categoryId: Long,
        bannerUrl: String?,
        bannerPath: String?,
        description: String,
    ) {
        val existing = cache[categoryId] ?: CollectionMetadata(categoryId)
        cache[categoryId] = existing.copy(
            bannerUrl = bannerUrl,
            bannerPath = bannerPath,
            description = description,
        )
        save()
    }

    fun saveBannerFile(categoryId: Long, sourceUri: Uri): String? {
        return runCatching {
            val dir = File(context.filesDir, "banners").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("$categoryId.") }?.forEach { it.delete() }
            val mime = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
            val ext = if (mime == "image/jpeg") "jpg" else mime.substringAfterLast('/')
            val target = File(dir, "$categoryId.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }.getOrNull()
    }

    fun markOpened(categoryId: Long) {
        if (categoryId <= 0L) return
        val existing = cache[categoryId] ?: CollectionMetadata(categoryId)
        cache[categoryId] = existing.copy(lastOpenedAt = System.currentTimeMillis())
        save()
    }

    fun getRecentCollections(limit: Int = 3): List<CollectionMetadata> {
        return cache.values
            .filter { it.lastOpenedAt > 0L }
            .sortedByDescending { it.lastOpenedAt }
            .take(limit)
    }
}
