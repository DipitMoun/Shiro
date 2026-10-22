package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.animesource.AnimeSource
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
    override val id: Long = 987654321L
    override val name: String = "[Telegram] Vidéo"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    // TODO: Brancher TDLib ici pour récupérer les groupes configurés
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun popularAnimeParse(response: Response): AnimesPage {
        return AnimesPage(emptyList(), false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun latestUpdatesParse(response: Response): AnimesPage {
        return AnimesPage(emptyList(), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(
        "$baseUrl/search",
    )
    override fun searchAnimeParse(response: Response): AnimesPage {
        return AnimesPage(emptyList(), false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return SAnime.create()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return emptyList()
    }

    override fun videoListParse(response: Response): List<Video> {
        return emptyList()
    }
}
