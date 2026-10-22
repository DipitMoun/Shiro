package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response

class TelegramAnimeSource : AnimeHttpSource() {
    override val id: Long = ID
    override val name: String = "[Telegram] Vidéo"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(
        "$baseUrl/search",
    )
    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create()
    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()
    override fun videoListParse(response: Response): List<Video> = emptyList()

    companion object {
        const val ID = 987654321L
    }
}
