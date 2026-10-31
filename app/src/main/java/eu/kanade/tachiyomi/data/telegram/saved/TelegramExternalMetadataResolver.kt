package eu.kanade.tachiyomi.data.telegram.saved

import eu.kanade.tachiyomi.data.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.index.TelegramMediaKind
import eu.kanade.tachiyomi.data.telegram.index.TelegramResolvedWork
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

/** Fournisseurs de métadonnées disponibles en plus d'AniList. */
enum class TelegramMetadataProvider(val label: String) {
    ANILIST("AniList"),
    MAL("MyAnimeList"),
    KITSU("Kitsu"),
    SHIKIMORI("Shikimori"),
    OMDB("OMDb / IMDb"),
}

/**
 * Récupère une fiche depuis une API publique (Jikan pour MAL, Kitsu,
 * Shikimori). Aucun compte ni token n'est nécessaire.
 */
class TelegramExternalMetadataResolver(
    private val network: NetworkHelper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences: TelegramPreferences by injectLazy()

    suspend fun search(
        provider: TelegramMetadataProvider,
        title: String,
        kind: TelegramMediaKind,
    ): TelegramResolvedWork? = withContext(Dispatchers.IO) {
        val query = title.trim()
        if (query.isBlank() || provider == TelegramMetadataProvider.ANILIST) {
            return@withContext null
        }

        runCatching {
            when (provider) {
                TelegramMetadataProvider.MAL -> jikan(query, kind)
                TelegramMetadataProvider.KITSU -> kitsu(query, kind)
                TelegramMetadataProvider.SHIKIMORI -> shikimori(query, kind)
                TelegramMetadataProvider.OMDB -> omdb(query, kind)
                TelegramMetadataProvider.ANILIST -> null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Jikan (API publique MyAnimeList)
    // ------------------------------------------------------------------

    private fun jikan(query: String, kind: TelegramMediaKind): TelegramResolvedWork? {
        val type = if (kind == TelegramMediaKind.VIDEO) "anime" else "manga"
        val url = "https://api.jikan.moe/v4/$type?q=${encode(query)}&limit=1&sfw"
        val body = fetch(url) ?: return null
        val item = json.decodeFromString<JikanResponse>(body).data.firstOrNull() ?: return null

        val year = item.year ?: item.published?.prop?.from?.year
        return TelegramResolvedWork(
            query = query,
            providerId = item.mal_id?.toString(),
            provider = TelegramMetadataProvider.MAL.name,
            title = item.title,
            coverUrl = item.images?.jpg?.large_image_url ?: item.images?.jpg?.image_url,
            description = item.synopsis?.let(::stripHtml),
            genres = item.genres.mapNotNull { it.name },
            year = year,
            totalEpisodes = item.episodes,
            status = when (item.status?.lowercase()) {
                "finished airing", "finished" -> "FINISHED"
                "currently airing", "publishing" -> "RELEASING"
                "not yet aired", "upcoming" -> "NOT_YET_RELEASED"
                "on hiatus" -> "HIATUS"
                "discontinued" -> "CANCELLED"
                else -> null
            },
            score = 1.0,
            resolvedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------
    // Kitsu
    // ------------------------------------------------------------------

    private fun kitsu(query: String, kind: TelegramMediaKind): TelegramResolvedWork? {
        val type = if (kind == TelegramMediaKind.VIDEO) "anime" else "manga"
        val url = "https://kitsu.io/api/edge/$type?filter%5Btext%5D=${encode(query)}&page%5Blimit%5D=1"
        val body = fetch(url, accept = "application/vnd.api+json") ?: return null
        val item = json.decodeFromString<KitsuResponse>(body).data.firstOrNull() ?: return null
        val attributes = item.attributes ?: return null

        return TelegramResolvedWork(
            query = query,
            providerId = item.id,
            provider = TelegramMetadataProvider.KITSU.name,
            title = attributes.canonicalTitle,
            coverUrl = attributes.posterImage?.large ?: attributes.posterImage?.original,
            description = attributes.synopsis?.let(::stripHtml),
            year = attributes.startDate?.take(4)?.toIntOrNull(),
            totalEpisodes = attributes.episodeCount,
            status = when (attributes.status?.lowercase()) {
                "finished" -> "FINISHED"
                "current" -> "RELEASING"
                "tba", "unreleased" -> "NOT_YET_RELEASED"
                else -> null
            },
            score = 1.0,
            resolvedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------
    // Shikimori
    // ------------------------------------------------------------------

    private fun shikimori(query: String, kind: TelegramMediaKind): TelegramResolvedWork? {
        val type = if (kind == TelegramMediaKind.VIDEO) "animes" else "mangas"
        val url = "https://shikimori.one/api/$type?search=${encode(query)}&limit=1"
        val body = fetch(url, userAgent = "Shiro/1.0") ?: return null
        val item = json.decodeFromString<List<ShikimoriItem>>(body).firstOrNull() ?: return null

        return TelegramResolvedWork(
            query = query,
            providerId = item.id?.toString(),
            provider = TelegramMetadataProvider.SHIKIMORI.name,
            title = item.name ?: item.russian,
            coverUrl = item.image?.original,
            description = item.description?.let(::stripHtml),
            year = item.aired_on?.take(4)?.toIntOrNull(),
            totalEpisodes = item.episodes,
            status = when (item.status?.lowercase()) {
                "released" -> "FINISHED"
                "ongoing" -> "RELEASING"
                "anons" -> "NOT_YET_RELEASED"
                else -> null
            },
            score = 1.0,
            resolvedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------
    // OMDb (IMDb)
    // ------------------------------------------------------------------

    private fun omdb(query: String, kind: TelegramMediaKind): TelegramResolvedWork? {
        val apiKey = preferences.omdbApiKey.trim()
        if (apiKey.isBlank()) return null

        val type = if (kind == TelegramMediaKind.VIDEO) "series" else "movie"
        val searchUrl = "$OMDB_URL?apikey=${encode(apiKey)}&s=${encode(query)}&type=$type"
        val searchBody = fetch(searchUrl) ?: return null
        val search = runCatching {
            json.decodeFromString<OmdbSearchResponse>(searchBody)
        }.getOrNull()

        val candidate = search?.search?.firstOrNull()
        val directTitle = if (candidate == null) {
            val directUrl = "$OMDB_URL?apikey=${encode(apiKey)}&t=${encode(query)}&type=$type&plot=full"
            fetch(directUrl)?.let {
                runCatching { json.decodeFromString<OmdbItem>(it) }.getOrNull()
            }
        } else {
            null
        }

        val details = candidate?.imdbID?.let { imdbId ->
            val url = "$OMDB_URL?apikey=${encode(apiKey)}&i=${encode(imdbId)}&plot=full"
            fetch(url)?.let { runCatching { json.decodeFromString<OmdbItem>(it) }.getOrNull() }
        } ?: directTitle

        val imdbId = details?.imdbID ?: candidate?.imdbID ?: return null
        if (details?.response == "False") return null

        return TelegramResolvedWork(
            query = query,
            providerId = imdbId,
            provider = TelegramMetadataProvider.OMDB.name,
            title = details?.title ?: candidate?.title,
            coverUrl = (details?.poster ?: candidate?.poster)?.takeUnless { it.isBlank() || it == "N/A" },
            description = details?.plot?.takeUnless { it.isBlank() || it == "N/A" },
            genres = details?.genre
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            year = (details?.year ?: candidate?.year)?.take(4)?.toIntOrNull(),
            score = details?.imdbRating?.toDoubleOrNull()?.div(10.0) ?: 1.0,
            resolvedAt = System.currentTimeMillis(),
        )
    }

    private fun fetch(
        url: String,
        accept: String = "application/json",
        userAgent: String? = null,
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", userAgent ?: "Shiro/1.0")
            .build()

        return network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun stripHtml(value: String): String = value
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    // ------------------------------------------------------------------
    // Modèles
    // ------------------------------------------------------------------

    @Serializable
    private data class JikanResponse(val data: List<JikanItem> = emptyList())

    @Serializable
    private data class JikanItem(
        val mal_id: Int? = null,
        val title: String? = null,
        val images: JikanImages? = null,
        val synopsis: String? = null,
        val genres: List<JikanGenre> = emptyList(),
        val year: Int? = null,
        val episodes: Int? = null,
        val status: String? = null,
        val published: JikanPublished? = null,
    )

    @Serializable
    private data class JikanImages(val jpg: JikanJpg? = null)

    @Serializable
    private data class JikanJpg(
        val image_url: String? = null,
        val large_image_url: String? = null,
    )

    @Serializable
    private data class JikanGenre(val name: String? = null)

    @Serializable
    private data class JikanPublished(val prop: JikanProp? = null)

    @Serializable
    private data class JikanProp(val from: JikanDate? = null)

    @Serializable
    private data class JikanDate(val year: Int? = null)

    @Serializable
    private data class KitsuResponse(val data: List<KitsuItem> = emptyList())

    @Serializable
    private data class KitsuItem(
        val id: String? = null,
        val attributes: KitsuAttributes? = null,
    )

    @Serializable
    private data class KitsuAttributes(
        val canonicalTitle: String? = null,
        val posterImage: KitsuPoster? = null,
        val synopsis: String? = null,
        val startDate: String? = null,
        val episodeCount: Int? = null,
        val status: String? = null,
    )

    @Serializable
    private data class KitsuPoster(
        val large: String? = null,
        val original: String? = null,
    )

    @Serializable
    private data class ShikimoriItem(
        val id: Int? = null,
        val name: String? = null,
        val russian: String? = null,
        val image: ShikimoriImage? = null,
        val description: String? = null,
        val status: String? = null,
        val episodes: Int? = null,
        val aired_on: String? = null,
    )

    @Serializable
    private data class ShikimoriImage(
        val original: String? = null,
        val preview: String? = null,
    )

    @Serializable
    private data class OmdbSearchResponse(
        @SerialName("Search") val search: List<OmdbSearchItem> = emptyList(),
        @SerialName("Response") val response: String? = null,
        @SerialName("Error") val error: String? = null,
    )

    @Serializable
    private data class OmdbSearchItem(
        @SerialName("imdbID") val imdbID: String? = null,
        @SerialName("Title") val title: String? = null,
        @SerialName("Year") val year: String? = null,
        @SerialName("Poster") val poster: String? = null,
    )

    @Serializable
    private data class OmdbItem(
        @SerialName("Title") val title: String? = null,
        @SerialName("Year") val year: String? = null,
        @SerialName("Poster") val poster: String? = null,
        @SerialName("Plot") val plot: String? = null,
        @SerialName("Genre") val genre: String? = null,
        @SerialName("imdbRating") val imdbRating: String? = null,
        @SerialName("imdbID") val imdbID: String? = null,
        @SerialName("Response") val response: String? = null,
        @SerialName("Error") val error: String? = null,
    )

    private companion object {
        const val OMDB_URL = "https://www.omdbapi.com/"
    }
}
