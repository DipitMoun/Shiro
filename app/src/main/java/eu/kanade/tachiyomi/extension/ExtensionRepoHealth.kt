package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader
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

    /** Stable text of the problems, to notify only when something changes. */
    fun signature(report: List<RepoHealth>): String =
        report.filter { it.hasProblem }
            .sortedBy { it.url }
            .joinToString("|") { "${it.url}:${it.status}:${it.incompatible.size}" }
}

/** Looks at every anime store and manga repo the user has added. */
class ExtensionRepoHealthChecker(
    private val getAnimeStores: GetAnimeExtensionStores = Injekt.get(),
    private val animeService: AnimeExtensionStoreService = Injekt.get(),
    private val getMangaRepos: GetMangaExtensionRepo = Injekt.get(),
) {

    suspend fun checkAll(): List<RepoHealth> = withIOContext {
        val anime = getAnimeStores.get().map { store ->
            val result = animeService.getExtensions(store)
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
        val manga = getMangaRepos.getAll().map { repo ->
            val probe = api.probe(repo)
            RepoHealthRules.classify(
                anime = false,
                name = repo.shortName ?: repo.name,
                url = repo.baseUrl,
                extensions = if (probe.error != null) null else probe.extensions.map { RepoExtension(it.name, it.libVersion) },
                error = probe.error?.message,
                outdatedStub = probe.stubWithoutNewIndex,
            )
        }
        anime + manga
    }
}
