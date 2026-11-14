package eu.kanade.tachiyomi.data.multisource

import android.content.Context
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistance locale des œuvres multi-source.
 *
 * L'URL sert d'identifiant stable au source adaptateur ; les métadonnées
 * complètes restent disponibles après redémarrage, notamment pour une œuvre
 * ajoutée à la bibliothèque.
 */
class MultiSourceWorkStore(context: Context) {
    private val prefs = context.getSharedPreferences("multi_source_works", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun urlFor(work: CatalogWork): String = "$URL_PREFIX${work.id}"

    fun put(work: CatalogWork): String {
        val url = urlFor(work)
        prefs.edit().putString(url, json.encodeToString(work)).apply()
        return url
    }

    fun get(url: String): CatalogWork? {
        val raw = prefs.getString(url, null) ?: return null
        return runCatching { json.decodeFromString<CatalogWork>(raw) }.getOrNull()
    }

    /** Source choisie manuellement pour une œuvre (null = automatique). */
    fun preferredSource(url: String): Long? = prefs.getLong("$url$SOURCE_SUFFIX", -1L).takeIf { it > 0L }

    fun setPreferredSource(url: String, sourceId: Long?) {
        prefs.edit().apply {
            if (sourceId == null || sourceId <= 0L) {
                remove("$url$SOURCE_SUFFIX")
            } else {
                putLong("$url$SOURCE_SUFFIX", sourceId)
            }
        }.apply()
    }

    private companion object {
        const val URL_PREFIX = "multisource:"
        const val SOURCE_SUFFIX = "#source"
    }
}
