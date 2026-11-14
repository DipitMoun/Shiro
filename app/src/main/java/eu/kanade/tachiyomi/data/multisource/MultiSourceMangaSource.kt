package eu.kanade.tachiyomi.data.multisource

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.browse.catalog.preferredTitle
import logcat.LogPriority
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

/**
 * Source synthétique qui expose une œuvre du catalogue (AniList/MAL/Kitsu)
 * comme un manga lisible.
 *
 * Elle délègue les chapitres et les pages à la meilleure extension activée,
 * ce qui la rend compatible avec le lecteur, le téléchargement et la
 * bibliothèque sans ajouter de moteur parallèle.
 */
object MultiSourceMangaSource : HttpSource() {

    const val ID = -3L
    const val CHAPTER_MARKER = "#chapter="

    override val id: Long = ID
    override val name: String = "Multi-source"
    override val baseUrl: String = "https://multisource.local"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val store: MultiSourceWorkStore
        get() = Injekt.get()

    private val resolver: CatalogMangaSourceResolver by lazy { CatalogMangaSourceResolver() }

    /** Source réelle utilisée pour une URL de page donnée (images). */
    private val pageSources = Collections.synchronizedMap(LinkedHashMap<String, HttpSource>())

    override fun getFilterList(): FilterList = FilterList()

    // ------------------------------------------------------------------
    // Délégation
    // ------------------------------------------------------------------

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val work = store.get(manga.url) ?: return manga
        return work.toSManga(manga.url)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val work = store.get(manga.url) ?: return emptyList()
        return resolver.coverage(work, manga.url)?.chapters
            ?.entries
            ?.sortedBy { it.key }
            ?.map { (number, chapter) ->
                SChapter.create().apply {
                    url = "${manga.url}$CHAPTER_MARKER$number"
                    name = chapter.name.ifBlank { "Chapitre $number" }
                    chapter_number = number.toFloat()
                    date_upload = chapter.date_upload
                    scanlator = chapter.scanlator
                }
            }
            .orEmpty()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val workUrl = chapter.url.substringBefore(CHAPTER_MARKER)
        val number = chapter.url.substringAfter(CHAPTER_MARKER).toIntOrNull() ?: return emptyList()

        val resolved = resolver.pages(workUrl, number)
        if (resolved == null) {
            logcat(LogPriority.WARN) {
                "Multi-source manga : aucune source pour $workUrl chapitre $number"
            }
            return emptyList()
        }

        rememberPages(resolved)
        return resolved.pages
    }

    override suspend fun getImageUrl(page: Page): String {
        val source = sourceFor(page)
        if (source == null) {
            logcat(LogPriority.WARN) {
                "Multi-source manga : source image inconnue pour ${page.url}"
            }
            return page.imageUrl ?: page.url
        }
        return source.getImageUrl(page)
    }

    override suspend fun getImage(page: Page): Response {
        val source = sourceFor(page)
        if (source == null) {
            logcat(LogPriority.WARN) {
                "Multi-source manga : source image inconnue pour ${page.url}"
            }
            throw IllegalStateException("Multi-source manga : source image introuvable.")
        }
        return source.getImage(page)
    }

    private fun rememberPages(resolved: ResolvedMangaPages) {
        if (pageSources.size > MAX_PAGE_SOURCES) pageSources.clear()
        resolved.pages.forEach { page ->
            pageSources[page.url] = resolved.source
            page.imageUrl?.let { pageSources[it] = resolved.source }
        }
    }

    private fun sourceFor(page: Page): HttpSource? {
        return pageSources[page.url] ?: page.imageUrl?.let { pageSources[it] }
    }

    // ------------------------------------------------------------------
    // Endpoints factices (jamais appelés : la source n'est pas catalogue)
    // ------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/$page")

    override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/$page")

    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search")

    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    override fun chapterPageParse(response: Response): SChapter = SChapter.create()

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    override fun toString(): String = name

    // ------------------------------------------------------------------
    // Sélection manuelle de la source
    // ------------------------------------------------------------------

    suspend fun availableSources(mangaUrl: String): List<MangaSourceCoverage> {
        val work = store.get(mangaUrl) ?: return emptyList()
        return resolver.availableSources(work)
    }

    fun preferredSourceId(mangaUrl: String): Long? = store.preferredSource(mangaUrl)

    fun setPreferredSource(mangaUrl: String, sourceId: Long?) {
        store.setPreferredSource(mangaUrl, sourceId)
        store.get(mangaUrl)?.let(resolver::invalidate)
    }

    private const val MAX_PAGE_SOURCES = 1_000
}

fun CatalogWork.toSManga(url: String): SManga {
    return SManga.create().apply {
        this.url = url
        title = preferredTitle()
        thumbnail_url = coverUrl
        description = synopsis
        genre = genres.joinToString(", ")
        initialized = true
    }
}
