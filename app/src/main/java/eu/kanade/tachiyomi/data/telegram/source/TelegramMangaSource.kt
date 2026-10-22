package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Response

class TelegramMangaSource : HttpSource() {
    override val id: Long = 987654322L
    override val name: String = "[Telegram] Doc"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    // TODO: Brancher TDLib ici pour récupérer les groupes configurés
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun popularMangaParse(response: Response): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun latestUpdatesParse(response: Response): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/search")
    override fun searchMangaParse(response: Response): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return SManga.create()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return emptyList()
    }

    override fun pageListParse(response: Response): List<Page> {
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String {
        return ""
    }
}