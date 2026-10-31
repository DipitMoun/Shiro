package eu.kanade.tachiyomi.data.telegram

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TelegramPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("telegram_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var videoChats: Set<Long>
        get() = prefs.getStringSet("video_chats", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet("video_chats", value.map { it.toString() }.toSet()) }

    var docChats: Set<Long>
        get() = prefs.getStringSet("doc_chats", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet("doc_chats", value.map { it.toString() }.toSet()) }

    /**
     * Canaux volontairement masqués dans la source Telegram. Ils restent connus
     * et réactivables depuis le filtre, mais ne sont plus synchronisés.
     */
    var disabledChats: Set<Long>
        get() = prefs.getStringSet("disabled_chats", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet()
            ?: emptySet()
        set(value) = prefs.edit { putStringSet("disabled_chats", value.map { it.toString() }.toSet()) }

    /** Intervalle de synchronisation automatique, en minutes (15 minimum). */
    var syncIntervalMinutes: Int
        get() = prefs.getInt("sync_interval_minutes", DEFAULT_SYNC_MINUTES).coerceAtLeast(15)
        set(value) = prefs.edit { putInt("sync_interval_minutes", value.coerceAtLeast(15)) }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit { putBoolean("notifications_enabled", value) }

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_enabled", true)
        set(value) = prefs.edit { putBoolean("auto_sync_enabled", value) }

    var lastGlobalSync: Long
        get() = prefs.getLong("last_global_sync", 0L)
        set(value) = prefs.edit { putLong("last_global_sync", value) }

    var isConnected: Boolean
        get() = prefs.getBoolean("is_connected", false)
        set(value) = prefs.edit { putBoolean("is_connected", value) }

    /** Clé gratuite OMDb, utilisée uniquement pour les métadonnées IMDb. */
    var omdbApiKey: String
        get() = prefs.getString("omdb_api_key", "").orEmpty()
        set(value) = prefs.edit { putString("omdb_api_key", value.trim()) }

    /** Les anciennes URLs d'épisodes (fileId seul) ont été régénérées avec messageId. */
    var episodeUrlsMigrated: Boolean
        get() = prefs.getBoolean("episode_urls_migrated", false)
        set(value) = prefs.edit { putBoolean("episode_urls_migrated", value) }

    /** Version de la réparation locale des œuvres Telegram déjà importées. */
    var savedWorksRepairVersion: Int
        get() = prefs.getInt("saved_works_repair_version", 0)
        set(value) = prefs.edit { putInt("saved_works_repair_version", value) }

    /** Version du dernier re-parsing local complet des index Telegram. */
    var indexRepairVersion: Int
        get() = prefs.getInt("index_repair_version", 0)
        set(value) = prefs.edit { putInt("index_repair_version", value) }

    /** Titres des canaux connus, même avant le premier index. */
    var channelTitles: Map<Long, String>
        get() = readLongMap(KEY_CHANNEL_TITLES)
        set(value) = writeLongMap(KEY_CHANNEL_TITLES, value)

    /** Œuvre forcée par canal (canaux mono-œuvre). */
    var forcedWorks: Map<Long, String>
        get() = readLongMap(KEY_FORCED_WORKS)
        set(value) = writeLongMap(KEY_FORCED_WORKS, value)

    fun forcedWork(chatId: Long): String? = forcedWorks[chatId]?.takeIf { it.isNotBlank() }

    fun setForcedWork(chatId: Long, title: String?) {
        val updated = forcedWorks.toMutableMap()
        if (title.isNullOrBlank()) {
            updated.remove(chatId)
        } else {
            updated[chatId] = title.trim()
        }
        forcedWorks = updated
    }

    fun channelTitle(chatId: Long): String? = channelTitles[chatId]?.takeIf { it.isNotBlank() }

    fun rememberChannel(chatId: Long, title: String) {
        if (title.isBlank()) return
        val updated = channelTitles.toMutableMap()
        updated[chatId] = title
        channelTitles = updated
    }

    fun forgetChannel(chatId: Long) {
        val titles = channelTitles.toMutableMap()
        titles.remove(chatId)
        channelTitles = titles
        val forced = forcedWorks.toMutableMap()
        forced.remove(chatId)
        forcedWorks = forced
        videoChats = videoChats - chatId
        docChats = docChats - chatId
        disabledChats = disabledChats - chatId
    }

    fun removeAll() {
        prefs.edit {
            remove(KEY_CHANNEL_TITLES)
            remove(KEY_FORCED_WORKS)
            remove("video_chats")
            remove("doc_chats")
            remove("disabled_chats")
            remove("omdb_api_key")
            remove("index_repair_version")
            remove("last_global_sync")
            putBoolean("is_connected", false)
        }
    }

    /** Tous les canaux connus, y compris ceux masqués par le filtre. */
    val knownChatIds: Set<Long>
        get() = videoChats + docChats + channelTitles.keys

    /** Canaux réellement synchronisés et visibles dans la source. */
    val allChatIds: Set<Long>
        get() = knownChatIds - disabledChats

    private fun readLongMap(key: String): Map<Long, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw)
                .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeLongMap(key: String, value: Map<Long, String>) {
        val raw = json.encodeToString(value.mapKeys { it.key.toString() })
        prefs.edit { putString(key, raw) }
    }

    companion object {
        const val DEFAULT_SYNC_MINUTES = 15
        private const val KEY_CHANNEL_TITLES = "channel_titles"
        private const val KEY_FORCED_WORKS = "forced_works"
    }
}

/**
 * Canaux réellement sélectionnés pour la recherche : mode Vidéo ou Documents,
 * jamais un canal simplement connu, jamais un canal désactivé.
 *
 * Fonction pure pour être testable sans Android.
 */
fun selectedTelegramChatIds(
    videoChats: Set<Long>,
    docChats: Set<Long>,
    disabledChats: Set<Long>,
): Set<Long> = (videoChats + docChats) - disabledChats
