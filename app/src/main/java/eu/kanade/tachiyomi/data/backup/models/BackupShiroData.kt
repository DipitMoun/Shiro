package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Shiro-only data that does not live in the SQLDelight databases.
 *
 * Stored under a high proto number in [Backup] so Aniyomi/Mihon simply ignore it,
 * and every field is optional so older Shiro backups still decode.
 */
@Serializable
class BackupShiroData(
    @ProtoNumber(1) var collections: List<BackupCollectionMetadata> = emptyList(),
    @ProtoNumber(2) var sharedPreferences: List<BackupNamedPreferences> = emptyList(),
    @ProtoNumber(3) var telegramWorkOverridesJson: String? = null,
    @ProtoNumber(4) var telegramSavedWorksJson: String? = null,
) {
    fun isEmpty(): Boolean =
        collections.isEmpty() &&
            sharedPreferences.isEmpty() &&
            telegramWorkOverridesJson.isNullOrBlank() &&
            telegramSavedWorksJson.isNullOrBlank()
}

/**
 * Banner and description of a collection.
 *
 * [categoryId] is the id the category had when the backup was created; it is remapped
 * to the restored category through [BackupCategory.id].
 */
@Serializable
class BackupCollectionMetadata(
    @ProtoNumber(1) var type: String,
    @ProtoNumber(2) var categoryId: Long,
    @ProtoNumber(3) var bannerUrl: String? = null,
    @ProtoNumber(4) var description: String = "",
    @ProtoNumber(5) var lastOpenedAt: Long = 0L,
    @ProtoNumber(6) var bannerBytes: ByteArray? = null,
    @ProtoNumber(7) var bannerExtension: String? = null,
)

/** Content of a named SharedPreferences file (multi-source, Telegram, ...). */
@Serializable
class BackupNamedPreferences(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var prefs: List<BackupPreference> = emptyList(),
)
