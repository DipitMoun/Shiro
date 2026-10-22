package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response

class TelegramMangaSource : HttpSource() {
    override val id: Long = ID
    override val name: String = "[Telegram] Doc"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/search")
    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()
    override fun chapterListParse(response: Response): List<SChapter> = emptyList()
    override fun pageListParse(response: Response): List<Page> = emptyList()
    override fun imageUrlParse(response: Response): String = ""
    override fun chapterPageParse(response: Response): SChapter = SChapter.create()

    companion object {
        const val ID = 987654322L
    }
}
