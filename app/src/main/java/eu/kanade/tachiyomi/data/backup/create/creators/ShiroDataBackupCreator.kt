package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupCollectionMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupNamedPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupShiroData
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkOverrideStore
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Backs up the Shiro data kept outside the databases: collection banners, multi-source
 * mappings, Telegram settings and manual Telegram corrections.
 *
 * The Telegram file index is left out on purpose: it is large and rebuilt by a sync.
 */
class ShiroDataBackupCreator(
    private val context: Context,
    private val collectionMetadata: CollectionMetadataManager = Injekt.get(),
    private val telegramSavedWorks: TelegramSavedWorkStore = Injekt.get(),
    private val telegramOverrides: TelegramWorkOverrideStore = Injekt.get(),
) {

    operator fun invoke(options: BackupOptions): BackupShiroData? {
        val data = BackupShiroData()

        if (options.categories) {
            data.collections = backupCollections()
        }

        val sharedPreferences = mutableListOf<BackupNamedPreferences>()
        if (options.libraryEntries) {
            ShiroBackupPreferences.LIBRARY_FILES.forEach { name ->
                sharedPreferences += backupSharedPreferences(name, includePrivate = true)
            }
            data.telegramSavedWorksJson = telegramSavedWorks.exportJson()
            data.telegramWorkOverridesJson = telegramOverrides.exportJson()
        }
        if (options.appSettings) {
            sharedPreferences += backupSharedPreferences(
                ShiroBackupPreferences.TELEGRAM_FILE,
                includePrivate = options.privateSettings,
            )
        }
        data.sharedPreferences = sharedPreferences.filter { it.prefs.isNotEmpty() }

        return data.takeUnless { it.isEmpty() }
    }

    private fun backupCollections(): List<BackupCollectionMetadata> {
        return collectionMetadata.all().map { metadata ->
            val banner = metadata.bannerPath
                ?.let(::File)
                ?.takeIf { it.isFile && it.length() in 1..MAX_BANNER_BYTES }
            BackupCollectionMetadata(
                type = metadata.type,
                categoryId = metadata.categoryId,
                bannerUrl = metadata.bannerUrl,
                description = metadata.description,
                lastOpenedAt = metadata.lastOpenedAt,
                bannerBytes = banner?.let {
                    runCatching { it.readBytes() }
                        .onFailure { e -> logcat(LogPriority.WARN, e) { "Bannière non sauvegardée" } }
                        .getOrNull()
                },
                bannerExtension = banner?.extension,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun backupSharedPreferences(name: String, includePrivate: Boolean): BackupNamedPreferences {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            .filterKeys { key -> ShiroBackupPreferences.isBackedUp(name, key, includePrivate) }
            .mapNotNull { (key, value) ->
                when (value) {
                    is Int -> BackupPreference(key, IntPreferenceValue(value))
                    is Long -> BackupPreference(key, LongPreferenceValue(value))
                    is Float -> BackupPreference(key, FloatPreferenceValue(value))
                    is String -> BackupPreference(key, StringPreferenceValue(value))
                    is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
                    is Set<*> -> (value as? Set<String>)?.let { BackupPreference(key, StringSetPreferenceValue(it)) }
                    else -> null
                }
            }
        return BackupNamedPreferences(name, prefs)
    }

    private companion object {
        const val MAX_BANNER_BYTES = 8L * 1024 * 1024
    }
}

/** Named SharedPreferences files Shiro backs up, and the keys that must stay on the device. */
internal object ShiroBackupPreferences {
    const val TELEGRAM_FILE = "telegram_prefs"
    val LIBRARY_FILES = listOf("multi_source_works", "multi_source_mappings")

    private val TELEGRAM_DEVICE_STATE = setOf(
        "is_connected",
        "last_global_sync",
        "episode_urls_migrated",
        "saved_works_repair_version",
        "index_repair_version",
    )
    private val TELEGRAM_PRIVATE = setOf("omdb_api_key")

    val ALL_FILES: Set<String> = (LIBRARY_FILES + TELEGRAM_FILE).toSet()

    fun isBackedUp(file: String, key: String, includePrivate: Boolean): Boolean {
        if (file != TELEGRAM_FILE) return true
        if (key in TELEGRAM_DEVICE_STATE) return false
        return includePrivate || key !in TELEGRAM_PRIVATE
    }
}
