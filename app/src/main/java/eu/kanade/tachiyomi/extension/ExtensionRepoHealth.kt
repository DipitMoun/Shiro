package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.api.MangaExtensionApi
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import mihon.data.extension.anime.service.AnimeExtensionStoreService
import mihon.domain.extension.anime.interactor.GetAnimeExtensionStores
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class RepoStatus {
    /** Answers and every extension can be loaded by this version of the app. */
    OK,

    /** Answers, but some extensions use a library version the app does not support. */
    PARTLY_INCOMPATIBLE,

    /** Only serves a "update your app" placeholder at the old address. */
    OUTDATED_FORMAT,

    /** Does not answer, or answers something the app cannot read. */
    UNREACHABLE,

    /** Answers with no extension at all. */
    EMPTY,
}

data class RepoHealth(
    val anime: Boolean,
    val name: String,
    val url: String,
    val status: RepoStatus,
    val total: Int,
    /** "Name (lib x)" of every extension the app cannot load. */
    val incompatible: List<String> = emptyList(),
    val note: String? = null,
) {
    val hasProblem: Boolean get() = status != RepoStatus.OK
}

/** One extension as the health check sees it. */
data class RepoExtension(val name: String, val libVersion: Double)

/** An installed extension, reduced to what the signature check needs. */
data class InstalledInfo(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    /** SHA-256 of the APK signature, null when unknown. */
    val signature: String?,
)

/** An extension a repo offers, with the key that repo signs with. */
data class AvailableInfo(
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val signingKey: String,
    val repoName: String,
)

/**
 * A newer version exists, but Android will not install it over the current one because it is
 * signed with another key (the extension came from another repo or app). The app hides such
 * updates; the fix is to uninstall, then install again from [repoName].
 */
data class BlockedUpdate(
    val anime: Boolean,
    val name: String,
    val pkgName: String,
    val installedVersion: String,
    val availableVersion: String,
    val repoName: String,
)

data class HealthReport(
    val repos: List<RepoHealth>,
    val blocked: List<BlockedUpdate>,
) {
    val hasProblem: Boolean get() = blocked.isNotEmpty() || repos.any { it.hasProblem }
}

/** The rules, kept apart from the network so they can be tested. */
object RepoHealthRules {

    fun animeLibSupported(lib: Double): Boolean = lib in AnimeExtensionLoader.SUPPORTED_LIB_VERSIONS

    fun mangaLibSupported(lib: Double): Boolean =
        lib >= MangaExtensionLoader.LIB_VERSION_MIN && lib <= MangaExtensionLoader.LIB_VERSION_MAX

    fun classify(
        anime: Boolean,
        name: String,
        url: String,
        extensions: List<RepoExtension>?,
        error: String?,
        outdatedStub: Boolean,
        supported: (Double) -> Boolean = if (anime) ::animeLibSupported else ::mangaLibSupported,
    ): RepoHealth {
        if (extensions == null) {
            return RepoHealth(anime, name, url, RepoStatus.UNREACHABLE, 0, note = error ?: "Pas de réponse")
        }
        if (outdatedStub) {
            return RepoHealth(
                anime, name, url, RepoStatus.OUTDATED_FORMAT, extensions.size,
                note = "Ce dépôt ne publie plus son catalogue à l'ancienne adresse.",
            )
        }
        if (extensions.isEmpty()) {
            return RepoHealth(anime, name, url, RepoStatus.EMPTY, 0, note = "Aucune extension listée.")
        }
        val unsupported = extensions
            .filterNot { supported(it.libVersion) }
            .map { "${it.name} (lib ${it.libVersion.toString().removeSuffix(".0")})" }
            .sorted()
        return RepoHealth(
            anime = anime,
            name = name,
            url = url,
            status = if (unsupported.isEmpty()) RepoStatus.OK else RepoStatus.PARTLY_INCOMPATIBLE,
            total = extensions.size,
            incompatible = unsupported,
        )
    }

    private fun sameKey(installed: String?, offered: String): Boolean =
        installed == null ||
            offered.isBlank() ||
            offered == "NO_SIGNING_KEY" ||
            offered.startsWith("NOFINGERPRINT") ||
            offered.equals(installed, ignoreCase = true)

    fun blockedUpdates(
        anime: Boolean,
        installed: List<InstalledInfo>,
        available: List<AvailableInfo>,
    ): List<BlockedUpdate> {
        val byPackage = available.groupBy { it.pkgName }
        return installed.mapNotNull { ext ->
            val newer = byPackage[ext.pkgName].orEmpty().filter { it.versionCode > ext.versionCode }
            if (newer.isEmpty()) return@mapNotNull null
            // One newer build this install accepts is enough: the normal update path works.
            if (newer.any { sameKey(ext.signature, it.signingKey) }) return@mapNotNull null
            val best = newer.maxBy { it.versionCode }
            BlockedUpdate(anime, ext.name, ext.pkgName, ext.versionName, best.versionName, best.repoName)
        }
    }

    /** Stable text of the problems, to notify only when something changes. */
    fun signature(report: HealthReport): String =
        (
            report.repos.filter { it.hasProblem }
                .sortedBy { it.url }
                .map { "${it.url}:${it.status}:${it.incompatible.size}" } +
                report.blocked.sortedBy { it.pkgName }.map { "blocked:${it.pkgName}:${it.availableVersion}" }
            ).joinToString("|")
}

/** Looks at every anime store and manga repo the user has added. */
class ExtensionRepoHealthChecker(
    private val getAnimeStores: GetAnimeExtensionStores = Injekt.get(),
    private val animeService: AnimeExtensionStoreService = Injekt.get(),
    private val getMangaRepos: GetMangaExtensionRepo = Injekt.get(),
    private val animeManager: AnimeExtensionManager = Injekt.get(),
    private val mangaManager: MangaExtensionManager = Injekt.get(),
) {

    suspend fun checkAll(): HealthReport = withIOContext {
        val animeAvailable = mutableListOf<AvailableInfo>()
        val anime = getAnimeStores.get().map { store ->
            val result = animeService.getExtensions(store)
            result.getOrNull()?.forEach {
                animeAvailable += AvailableInfo(it.pkgName, it.versionName, it.versionCode, store.signingKey, store.name)
            }
            RepoHealthRules.classify(
                anime = true,
                name = store.name,
                url = store.indexUrl,
                extensions = result.getOrNull()?.map { RepoExtension(it.name, it.libVersion) },
                error = result.exceptionOrNull()?.message,
                outdatedStub = false,
            )
        }

        val api = MangaExtensionApi()
        val mangaAvailable = mutableListOf<AvailableInfo>()
        val manga = getMangaRepos.getAll().map { repo ->
            val probe = api.probe(repo)
            val name = repo.shortName ?: repo.name
            probe.extensions.forEach {
                mangaAvailable += AvailableInfo(
                    it.pkgName,
                    it.versionName,
                    it.versionCode,
                    it.signingKey.ifBlank { repo.signingKeyFingerprint },
                    name,
                )
            }
            RepoHealthRules.classify(
                anime = false,
                name = name,
                url = repo.baseUrl,
                extensions = if (probe.error != null) null else probe.extensions.map { RepoExtension(it.name, it.libVersion) },
                error = probe.error?.message,
                outdatedStub = probe.stubWithoutNewIndex,
            )
        }

        val blocked = RepoHealthRules.blockedUpdates(
            anime = true,
            installed = animeManager.installedExtensionsFlow.value.map {
                InstalledInfo(it.name, it.pkgName, it.versionName, it.versionCode, it.signatureHash)
            },
            available = animeAvailable,
        ) + RepoHealthRules.blockedUpdates(
            anime = false,
            installed = mangaManager.installedExtensionsFlow.value.map {
                InstalledInfo(it.name, it.pkgName, it.versionName, it.versionCode, it.signatureHash)
            },
            available = mangaAvailable,
        )

        HealthReport(anime + manga, blocked)
    }
}
