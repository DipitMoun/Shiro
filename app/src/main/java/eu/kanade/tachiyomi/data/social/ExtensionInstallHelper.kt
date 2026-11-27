package eu.kanade.tachiyomi.data.social

import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.flow.collect
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Checks and installs the extension a friend's [WorkSourceRef] needs. */
object ExtensionInstallHelper {

    fun isInstalled(ref: WorkSourceRef): Boolean =
        if (ref.isManga) {
            Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value.any { it.pkgName == ref.pkg }
        } else {
            Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value.any { it.pkgName == ref.pkg }
        }

    /**
     * Starts the installation from the repos the user has. False when none of their repos offers
     * the extension (they must add the right repo first).
     */
    suspend fun install(ref: WorkSourceRef): Boolean {
        if (ref.isManga) {
            val manager = Injekt.get<MangaExtensionManager>()
            manager.findAvailableExtensions()
            val available = manager.availableExtensionsFlow.value.firstOrNull { it.pkgName == ref.pkg } ?: return false
            manager.installExtension(available).collect()
        } else {
            val manager = Injekt.get<AnimeExtensionManager>()
            manager.findAvailableExtensions()
            val available = manager.availableExtensionsFlow.value.firstOrNull { it.pkgName == ref.pkg } ?: return false
            manager.installExtension(available).collect()
        }
        return true
    }
}
