package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.backup.create.creators.ShiroBackupPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNamedPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupShiroData
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.data.collection.CollectionType
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkOverrideStore
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Restores the data written by [eu.kanade.tachiyomi.data.backup.create.creators.ShiroDataBackupCreator]. */
class ShiroDataRestorer(
    private val context: Context,
    private val collectionMetadata: CollectionMetadataManager = Injekt.get(),
    private val telegramSavedWorks: TelegramSavedWorkStore = Injekt.get(),
    private val telegramOverrides: TelegramWorkOverrideStore = Injekt.get(),
) {

    /**
     * @param restoredAnimeCategories / [restoredMangaCategories] map each backup category
     * to its restored database id; collection metadata is keyed by category id, which
     * changes on restore.
     */
    fun restore(
        data: BackupShiroData,
        restoredAnimeCategories: Map<BackupCategory, Long>,
        restoredMangaCategories: Map<BackupCategory, Long>,
    ) {
        restoreCollections(data, restoredAnimeCategories.byBackupId(), restoredMangaCategories.byBackupId())
        data.sharedPreferences
            .filter { it.name in ShiroBackupPreferences.ALL_FILES }
            .forEach(::restoreSharedPreferences)
        data.telegramSavedWorksJson?.takeIf { it.isNotBlank() }?.let(telegramSavedWorks::importJson)
        data.telegramWorkOverridesJson?.takeIf { it.isNotBlank() }?.let(telegramOverrides::importJson)
    }

    private fun restoreCollections(
        data: BackupShiroData,
        animeIds: Map<Long, Long>,
        mangaIds: Map<Long, Long>,
    ) {
        data.collections.forEach { collection ->
            val type = CollectionType.fromKey(collection.type)
            val ids = if (type == CollectionType.MANGA) mangaIds else animeIds
            val categoryId = ids[collection.categoryId] ?: return@forEach
            collectionMetadata.restore(
                type = type,
                categoryId = categoryId,
                bannerUrl = collection.bannerUrl,
                bannerBytes = collection.bannerBytes,
                bannerExtension = collection.bannerExtension,
                description = collection.description,
                lastOpenedAt = collection.lastOpenedAt,
            )
        }
    }

    private fun restoreSharedPreferences(backup: BackupNamedPreferences) {
        context.getSharedPreferences(backup.name, Context.MODE_PRIVATE).edit(commit = true) {
            backup.prefs
                .filter { ShiroBackupPreferences.isBackedUp(backup.name, it.key, includePrivate = true) }
                .forEach { pref ->
                    when (val value = pref.value) {
                        is IntPreferenceValue -> putInt(pref.key, value.value)
                        is LongPreferenceValue -> putLong(pref.key, value.value)
                        is FloatPreferenceValue -> putFloat(pref.key, value.value)
                        is StringPreferenceValue -> putString(pref.key, value.value)
                        is BooleanPreferenceValue -> putBoolean(pref.key, value.value)
                        is StringSetPreferenceValue -> putStringSet(pref.key, value.value)
                    }
                }
        }
    }

    private fun Map<BackupCategory, Long>.byBackupId(): Map<Long, Long> =
        entries.filter { it.key.id > 0L }.associate { it.key.id to it.value }
}
