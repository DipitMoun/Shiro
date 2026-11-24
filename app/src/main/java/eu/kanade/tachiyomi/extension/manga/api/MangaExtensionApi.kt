package eu.kanade.tachiyomi.extension.manga.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import mihon.domain.extensionrepo.manga.interactor.UpdateMangaExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class MangaExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetMangaExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateMangaExtensionRepo by injectLazy()
    private val extensionManager: MangaExtensionManager by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong("last_ext_check", 0)
    }

    suspend fun findExtensions(): List<MangaExtension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
                .bestPerPackage()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<MangaExtension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        return try {
            val response = networkService.client
                .newCall(GET("$repoBaseUrl/index.min.json"))
                .awaitSuccess()

            val legacy = with(json) {
                response
                    .parseAs<List<ExtensionJsonObject>>()
                    .toExtensions(repoBaseUrl, extRepo.signingKeyFingerprint)
            }

            // Some repos (Keiyoushi) now serve a two-entry stub here that only tells old apps to
            // update, and publish the real catalogue in the extensions-lib 1.6 index.
            if (legacy.size > LEGACY_STUB_MAX_ENTRIES) {
                legacy
            } else {
                fetchNewIndex(repoBaseUrl, extRepo.signingKeyFingerprint)?.takeIf { it.isNotEmpty() } ?: legacy
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    /** The extensions-lib 1.6 index, or null when the repo does not publish one. */
    private suspend fun fetchNewIndex(repoBaseUrl: String, fallbackSigningKey: String): List<MangaExtension.Available>? {
        return try {
            val body = networkService.client
                .newCall(GET("$repoBaseUrl/index.json"))
                .awaitSuccess()
                .use { it.body.string() }
            parseNewIndex(json, body, repoBaseUrl, fallbackSigningKey)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "No usable 1.6 index at $repoBaseUrl" }
            null
        }
    }

    // Several repos can ship the same package: keep only the highest versionCode so that
    // "update available" and "install" always use the same entry (Android refuses downgrades).
    private fun List<MangaExtension.Available>.bestPerPackage(): List<MangaExtension.Available> =
        groupBy { it.pkgName }.values.map { dups ->
            dups.maxWith(compareBy<MangaExtension.Available>({ it.versionCode }, { it.libVersion }))
        }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<MangaExtension.Installed>? {
        // Limit checks to once a day at most
        if (fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = MangaExtensionLoader.loadMangaExtensions(context)
            .filterIsInstance<MangaLoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<MangaExtension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            // libVersion alone is not an update: Android only accepts a strictly higher versionCode.
            // Also reject mismatched signatures: Android refuses INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            val sigOk = installedExt.signatureHash == null ||
                availableExt.signingKey == installedExt.signatureHash ||
                availableExt.signingKey.startsWith("NOFINGERPRINT") ||
                availableExt.signingKey == "NO_SIGNING_KEY"
            val hasUpdate = hasUpdatedVer && sigOk
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(
        repoUrl: String,
        signingKey: String,
    ): List<MangaExtension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= MangaExtensionLoader.LIB_VERSION_MIN && libVersion <= MangaExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                MangaExtension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                    signingKey = signingKey,
                )
            }
    }

    fun getApkUrl(extension: MangaExtension.Available): String {
        return extension.apkUrl ?: "${extension.repoUrl}/apk/${extension.apkName}"
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> MangaExtension.Available.MangaSource = {
    MangaExtension.Available.MangaSource(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}

// extensions-lib 1.6 index (https://.../index.json). Numbers may come as strings or numbers.
private const val LEGACY_STUB_MAX_ENTRIES = 3

@Serializable
private data class NewIndexJsonObject(
    val signingKey: String = "",
    val extensionList: NewExtensionListJsonObject? = null,
)

@Serializable
private data class NewExtensionListJsonObject(
    val extensions: List<NewExtensionJsonObject> = emptyList(),
)

@Serializable
private data class NewExtensionJsonObject(
    val name: String,
    val packageName: String,
    val resources: NewResourcesJsonObject,
    val extensionLib: String? = null,
    val versionCode: JsonPrimitive,
    val versionName: String,
    val contentWarning: String? = null,
    val sources: List<NewSourceJsonObject> = emptyList(),
)

@Serializable
private data class NewResourcesJsonObject(
    val apkUrl: String,
    val iconUrl: String? = null,
)

@Serializable
private data class NewSourceJsonObject(
    val id: JsonPrimitive,
    val name: String,
    val language: String,
    val homeUrl: String = "",
)

private fun NewExtensionJsonObject.toAvailable(
    repoUrl: String,
    signingKey: String,
): MangaExtension.Available? {
    val code = versionCode.content.toLongOrNull() ?: return null
    val lib = (extensionLib ?: versionName.substringBeforeLast('.')).toDoubleOrNull() ?: return null
    val mappedSources = sources.mapNotNull { source ->
        val id = source.id.content.toLongOrNull() ?: return@mapNotNull null
        MangaExtension.Available.MangaSource(id = id, lang = source.language, name = source.name, baseUrl = source.homeUrl)
    }
    // One language for the whole extension when its sources agree, "all" otherwise.
    val languages = mappedSources.map { it.lang }.distinct()
    return MangaExtension.Available(
        name = name.substringAfter("Tachiyomi: "),
        pkgName = packageName,
        versionName = versionName,
        versionCode = code,
        libVersion = lib,
        lang = languages.singleOrNull() ?: "all",
        isNsfw = contentWarning == "CONTENT_WARNING_NSFW",
        sources = mappedSources,
        apkName = resources.apkUrl.substringAfterLast('/'),
        iconUrl = resources.iconUrl ?: "$repoUrl/icon/$packageName.png",
        repoUrl = repoUrl,
        signingKey = signingKey,
        apkUrl = resources.apkUrl,
    )
}

/**
 * Reads an extensions-lib 1.6 index. Null when [body] is not in that format; extensions built for
 * a library version the app does not support are left out.
 */
internal fun parseNewIndex(
    json: Json,
    body: String,
    repoUrl: String,
    fallbackSigningKey: String,
): List<MangaExtension.Available>? {
    val index = runCatching { json.decodeFromString<NewIndexJsonObject>(body) }.getOrNull() ?: return null
    val extensions = index.extensionList?.extensions ?: return null
    val signingKey = fallbackSigningKey.ifBlank { index.signingKey }
    return extensions
        .mapNotNull { it.toAvailable(repoUrl, signingKey) }
        .filter {
            it.libVersion >= MangaExtensionLoader.LIB_VERSION_MIN &&
                it.libVersion <= MangaExtensionLoader.LIB_VERSION_MAX
        }
}
