package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramChannelScraper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class TelegramMangaSource : HttpSource() {
    override val id: Long = ID
    override val name: String = "[Telegram] Doc"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val scraper: TelegramChannelScraper by injectLazy()
    private val preferences: TelegramPreferences by injectLazy()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun popularMangaParse(response: Response): MangasPage = fetchMangas()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun latestUpdatesParse(response: Response): MangasPage = fetchMangas()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search")
    override fun searchMangaParse(response: Response): MangasPage = fetchMangas()

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    override fun chapterPageParse(response: Response): SChapter = SChapter.create()

    private fun fetchMangas(): MangasPage {
        val chatIds = preferences.docChats
        if (chatIds.isEmpty()) return MangasPage(emptyList(), false)

        val mangas = mutableListOf<SManga>()
        runBlocking {
            for (chatId in chatIds) {
                val files = runCatching { scraper.fetchFiles(chatId) }.getOrDefault(emptyList())
                mangas.addAll(
                    files.map { file ->
                        SManga.create().apply {
                            title = file.fileName
                            url = "/$chatId/${file.fileId}"
                            thumbnail_url = ""
                        }
                    },
                )
            }
        }
        return MangasPage(mangas, false)
    }

    companion object {
        const val ID = 987654322L
    }
}
