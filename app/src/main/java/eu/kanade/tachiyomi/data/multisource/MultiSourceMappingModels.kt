package eu.kanade.tachiyomi.data.multisource

import android.content.Context
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Rattachement d'un épisode de l'œuvre multi-source à un épisode d'une extension. */
@Serializable
data class MultiSourceEpisodeRef(
    val sourceId: Long,
    val sourceName: String,
    val animeUrl: String,
    val animeTitle: String? = null,
    val episodeUrl: String,
    val episodeNumber: Int,
    val episodeName: String,
)

/** Ligne de compatibilité affichée dans la modal des sources. */
data class MultiSourceCompatibility(
    val sourceId: Long,
    val sourceName: String,
    val extension: AnimeExtension.Installed?,
    val compatibility: Float,
    val coveredEpisodes: Int,
    val assignedEpisodes: Int,
)

@Serializable
data class PersistedSourceCompatibility(
    val sourceId: Long,
    val sourceName: String,
    val compatibility: Float,
    val coveredEpisodes: Int,
    val assignedEpisodes: Int,
)

@Serializable
data class MultiSourceMappingReport(
    val totalEpisodes: Int,
    val assignedEpisodes: Int,
    val complete: Boolean,
    val sources: List<PersistedSourceCompatibility>,
    val version: Int = 1,
    /** Sélection utilisée pour ce rapport ; null = toutes les sources. */
    val selectedSourceIds: List<Long>? = null,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/** État du mapping d'une œuvre multi-source. */
data class MultiSourceMappingState(
    val loading: Boolean = true,
    val totalEpisodes: Int = 0,
    val assignedEpisodes: Int = 0,
    val complete: Boolean = false,
    val sources: List<MultiSourceCompatibility> = emptyList(),
    val error: String? = null,
)

/**
 * Persiste les affectations épisode → source.
 *
 * L'URL d'épisode générée par l'adaptateur contient l'URL de l'œuvre et le
 * numéro d'épisode ; on peut donc retrouver la source à utiliser pour les
 * vidéos, la lecture et le téléchargement.
 */
class MultiSourceMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("multi_source_mappings", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Dernière œuvre ouverte. Sert de secours quand d'anciens épisodes ont encore
     * une URL sans l'URL de l'œuvre (`episode:12` au lieu de `work#episode=12`).
     */
    @Volatile
    var activeWorkUrl: String? = null

    fun save(workUrl: String, assignments: Map<Int, MultiSourceEpisodeRef>) {
        val encoded = assignments.mapKeys { it.key.toString() }
        prefs.edit().putString(workUrl, json.encodeToString(encoded)).apply()
    }

    fun get(workUrl: String): Map<Int, MultiSourceEpisodeRef> {
        val raw = prefs.getString(workUrl, null) ?: return emptyMap()
        val decoded = runCatching {
            json.decodeFromString<Map<String, MultiSourceEpisodeRef>>(raw)
        }.getOrNull().orEmpty()
        return decoded.mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }.toMap()
    }

    fun getByEpisodeUrl(episodeUrl: String): MultiSourceEpisodeRef? {
        val workUrl = episodeUrl.substringBefore(EPISODE_MARKER)
        if (workUrl == episodeUrl) return null
        val number = episodeUrl.substringAfter(EPISODE_MARKER).toIntOrNull() ?: return null
        return get(workUrl)[number]
    }

    fun getForEpisode(episodeUrl: String, episodeNumber: Int): MultiSourceEpisodeRef? {
        getByEpisodeUrl(episodeUrl)?.let { return it }
        val workUrl = activeWorkUrl ?: return null
        return get(workUrl)[episodeNumber]
    }

    /**
     * Sources de secours pour un épisode, dans l'ordre de compatibilité.
     * La source principale n'est pas incluse ici.
     */
    fun getAlternativesForEpisode(episodeUrl: String, episodeNumber: Int): List<MultiSourceEpisodeRef> {
        val workUrl = episodeUrl
            .substringBefore(EPISODE_MARKER)
            .takeIf { it != episodeUrl }
            ?: activeWorkUrl
            ?: return emptyList()
        return getAlternatives(workUrl)[episodeNumber].orEmpty()
    }

    fun saveAlternatives(
        workUrl: String,
        alternatives: Map<Int, List<MultiSourceEpisodeRef>>,
    ) {
        val key = "$workUrl$ALTERNATIVES_SUFFIX"
        if (alternatives.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encoded = alternatives.mapKeys { it.key.toString() }
        prefs.edit().putString(key, json.encodeToString(encoded)).apply()
    }

    fun getAlternatives(workUrl: String): Map<Int, List<MultiSourceEpisodeRef>> {
        val raw = prefs.getString("$workUrl$ALTERNATIVES_SUFFIX", null) ?: return emptyMap()
        val decoded = runCatching {
            json.decodeFromString<Map<String, List<MultiSourceEpisodeRef>>>(raw)
        }.getOrNull().orEmpty()
        return decoded.mapNotNull { (key, value) ->
            key.toIntOrNull()?.let { it to value }
        }.toMap()
    }

    fun saveReport(workUrl: String, report: MultiSourceMappingReport) {
        prefs.edit().putString("$workUrl$REPORT_SUFFIX", json.encodeToString(report)).apply()
    }

    fun getReport(workUrl: String): MultiSourceMappingReport? {
        val raw = prefs.getString("$workUrl$REPORT_SUFFIX", null) ?: return null
        return runCatching { json.decodeFromString<MultiSourceMappingReport>(raw) }.getOrNull()
    }

    fun getSelectedSourceIds(): Set<Long>? {
        val raw = prefs.getStringSet(SELECTED_SOURCES_KEY, null) ?: return null
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    /**
     * Sources qui apparaissent le plus souvent dans les mappings existants.
     * Elles sont traitées en premier lors d'un remap pour qu'une source lente
     * mais déjà fiable (Voiranime, par exemple) ne soit pas victime du timeout
     * global au profit de sources jamais utilisées.
     */
    fun getFrequentlyUsedSourceIds(limit: Int = 8): Set<Long> {
        val counts = mutableMapOf<Long, Int>()
        prefs.all.values.filterIsInstance<String>().forEach { raw ->
            SOURCE_ID.findAll(raw).forEach { match ->
                val sourceId = match.groupValues[1].toLongOrNull() ?: return@forEach
                counts[sourceId] = (counts[sourceId] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toSet()
    }

    fun setSelectedSourceIds(ids: Set<Long>) {
        if (getSelectedSourceIds() == ids) return
        prefs.edit()
            .putStringSet(SELECTED_SOURCES_KEY, ids.map { it.toString() }.toSet())
            .apply()
    }

    companion object {
        const val EPISODE_MARKER = "#episode="
        private const val REPORT_SUFFIX = "#report"
        private const val ALTERNATIVES_SUFFIX = "#alternatives"
        private const val SELECTED_SOURCES_KEY = "selected_mapping_sources"
        private val SOURCE_ID = Regex("\"sourceId\"\\s*:\\s*(\\d+)")
    }
}
