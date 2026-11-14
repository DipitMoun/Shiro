package eu.kanade.tachiyomi.extension.anime.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader
import mihon.domain.extension.anime.interactor.UpdateAnimeExtensionStores
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class AnimeExtensionApi {

    private val repository: AnimeExtensionStoreRepository by injectLazy()

    private val preferenceStore: PreferenceStore by injectLazy()
    private val updateExtensionStores: UpdateAnimeExtensionStores by injectLazy()
    private val animeExtensionManager: AnimeExtensionManager by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong("last_ext_check", 0)
    }

    suspend fun findExtensions(
        installedByPkg: Map<String, AnimeExtension.Installed> = emptyMap(),
    ): List<AnimeExtension.Available> {
        return withIOContext { repository.fetchExtensions().bestPerPackage(installedByPkg) }
    }

    // Several repos can ship the same package: keep the highest versionCode whose store
    // signing key matches the installed APK signature, so that Android accepts the update.
    // Falls back to the overall highest versionCode only when no compatible entry exists
    // or when the installed signature is unknown (NO_SIGNING_KEY stores).
    private fun List<AnimeExtension.Available>.bestPerPackage(
        installedByPkg: Map<String, AnimeExtension.Installed>,
    ): List<AnimeExtension.Available> =
        groupBy { it.pkgName }.values.mapNotNull { dups ->
            val installedSig = installedByPkg[dups.first().pkgName]?.signatureHash
            val compatible = if (installedSig != null) {
                dups.filter { it.store.signingKey == installedSig || it.store.signingKey == "NO_SIGNING_KEY" }
            } else {
                dups
            }
            (compatible.ifEmpty { dups })
                .maxWithOrNull(compareBy<AnimeExtension.Available>({ it.versionCode }, { it.libVersion }))
        }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<AnimeExtension.Installed>? {
        // Limit checks to once a day at most
        if (fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionStores()

        val installedExtensions = AnimeExtensionLoader.loadExtensions(context)
            .filterIsInstance<AnimeLoadResult.Success>()
            .map { it.extension }

        val extensions = if (fromAvailableExtensionList) {
            animeExtensionManager.availableExtensionsFlow.value
        } else {
            val installedByPkg = installedExtensions.associateBy { it.pkgName }
            findExtensions(installedByPkg).also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val extensionsWithUpdate = mutableListOf<AnimeExtension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue

            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            // libVersion alone is not an update: Android only accepts a strictly higher versionCode.
            // Also reject mismatched signatures: Android refuses INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            val sigOk = installedExt.signatureHash == null ||
                availableExt.store.signingKey == installedExt.signatureHash ||
                availableExt.store.signingKey == "NO_SIGNING_KEY"
            val hasUpdate = hasUpdatedVer && sigOk
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(
                names = extensionsWithUpdate.map { it.name },
                anime = true,
            )
        }

        return extensionsWithUpdate
    }
}
