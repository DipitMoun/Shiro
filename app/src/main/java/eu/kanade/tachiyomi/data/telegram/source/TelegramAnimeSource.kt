package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramChannelScraper
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class TelegramAnimeSource : AnimeHttpSource() {
    override val id: Long = ID
    override val name: String = "[Telegram] Vidéo"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val scraper: TelegramChannelScraper by injectLazy()
    private val preferences: TelegramPreferences by injectLazy()

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun popularAnimeParse(response: Response): AnimesPage = fetchAnimes()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$page")
    override fun latestUpdatesParse(response: Response): AnimesPage = fetchAnimes()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search")
    override fun searchAnimeParse(response: Response): AnimesPage = fetchAnimes()

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create()

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    override fun videoListParse(response: Response): List<Video> = emptyList()

    private fun fetchAnimes(): AnimesPage {
        val chatIds = preferences.videoChats
        if (chatIds.isEmpty()) return AnimesPage(emptyList(), false)

        val animes = mutableListOf<SAnime>()
        runBlocking {
            for (chatId in chatIds) {
                val files = runCatching { scraper.fetchFiles(chatId) }.getOrDefault(emptyList())
                animes.addAll(
                    files.map { file ->
                        SAnime.create().apply {
                            title = file.fileName
                            url = "/$chatId/${file.fileId}"
                            thumbnail_url = ""
                        }
                    },
                )
            }
        }
        return AnimesPage(animes, false)
    }

    companion object {
        const val ID = 987654321L
    }
}
