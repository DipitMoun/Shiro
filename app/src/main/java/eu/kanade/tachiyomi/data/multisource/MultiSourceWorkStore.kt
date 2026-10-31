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

    private companion object {
        const val URL_PREFIX = "multisource:"
    }
}
