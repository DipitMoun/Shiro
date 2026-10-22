package eu.kanade.tachiyomi.data.collection

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CollectionMetadata(
    val categoryId: Long,
    val bannerUrl: String? = null,
    val description: String = "",
)

class CollectionMetadataManager(context: Context) {
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

    fun updateMetadata(categoryId: Long, bannerUrl: String?, description: String) {
        cache[categoryId] = CollectionMetadata(categoryId, bannerUrl, description)
        save()
    }
}
