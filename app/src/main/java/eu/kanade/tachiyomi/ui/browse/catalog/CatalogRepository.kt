package eu.kanade.tachiyomi.ui.browse.catalog

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Recherche catalogue multi-fournisseurs.
 *
 * Aucune source de lecture n'est interrogée ici : uniquement des métadonnées
 * (titre, affiche, résumé, nombre d'épisodes). Les sources ne seront
 * interrogées qu'au clic sur un épisode précis.
 */
class CatalogRepository(
    private val network: NetworkHelper,
    private val type: CatalogType = CatalogType.ANIME,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Jikan est régulièrement lent/limité : on ne le laisse jamais bloquer
    // l'affichage des résultats AniList/Kitsu.
    private val jikanClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private data class CachedDiscover(
        val feed: DiscoverFeed,
        val fetchedAt: Long,
    )

    private data class CachedRecommendations(
        val works: List<CatalogWork>,
        val fetchedAt: Long,
    )

    suspend fun search(
        query: String,
        onPartial: (List<CatalogWork>) -> Unit = {},
    ): List<CatalogWork> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // AniList + Kitsu répondent vite et sont affichés immédiatement.
        // Jikan/MAL est complémentaire : s'il est lent, il ne bloque pas les
        // deux premiers résultats.
        val baseProviders = listOf(
            async {
                runCatching {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { anilist(query) }
                }.getOrNull().orEmpty()
            },
            async {
                runCatching {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { kitsu(query) }
                }.getOrNull().orEmpty()
            },
        ).awaitAll()

        var merged = merge(baseProviders.flatten())
        onPartial(merged)

        val jikanResults = runCatching {
            withTimeoutOrNull(JIKAN_TIMEOUT_MS) { jikan(query) }
        }.getOrNull().orEmpty()

        if (jikanResults.isNotEmpty()) {
            merged = merge(merged + jikanResults)
            onPartial(merged)
        }

        merged
    }

    fun peekDiscover(): DiscoverFeed? {
        val now = System.currentTimeMillis()
        return synchronized(cacheLock) {
            discoverCache?.takeIf { now - it.fetchedAt < FEED_CACHE_TTL_MS }?.feed
        }
    }

    suspend fun discover(force: Boolean = false): DiscoverFeed = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = synchronized(cacheLock) {
            discoverCache?.takeIf { !force && now - it.fetchedAt < FEED_CACHE_TTL_MS }?.feed
        }
        if (cached != null) return@withContext cached

        val fetched = runCatching {
            withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { anilistDiscover() }
        }.getOrNull() ?: DiscoverFeed()

        if (!fetched.isEmpty) {
            synchronized(cacheLock) {
                discoverCache = CachedDiscover(fetched, now)
            }
        }
        fetched
    }

    suspend fun recommendations(
        seedTitle: String,
        force: Boolean = false,
    ): List<CatalogWork> = withContext(Dispatchers.IO) {
        if (seedTitle.isBlank()) return@withContext emptyList()

        val key = normalizeTitle(seedTitle)
        val now = System.currentTimeMillis()
        val cached = synchronized(cacheLock) {
            recommendationsCache[key]
                ?.takeIf { !force && now - it.fetchedAt < RECOMMENDATIONS_CACHE_TTL_MS }
                ?.works
        }
        if (cached != null) return@withContext cached

        val fetched = runCatching {
            withTimeoutOrNull(RECOMMENDATIONS_TIMEOUT_MS) { anilistRecommendations(seedTitle) }
        }.getOrNull().orEmpty()

        if (fetched.isNotEmpty()) {
            synchronized(cacheLock) {
                recommendationsCache[key] = CachedRecommendations(fetched, now)
            }
        }
        fetched
    }

    // ------------------------------------------------------------------
    // AniList
    // ------------------------------------------------------------------

    private fun anilist(query: String): List<CatalogWork> {
        val response = anilistRequest(
            ANILIST_QUERY.replace("type: ANIME", "type: " + type.name),
            buildJsonObject { put("search", query) },
        ) ?: return emptyList()

        val media = json.decodeFromString<AniListResponse>(response)
            .data?.Page?.media
            .orEmpty()

        return media.map(::anilistWork)
    }

    private fun anilistDiscover(): DiscoverFeed {
        val response = anilistRequest(DISCOVER_QUERY.replace("type: ANIME", "type: " + type.name)) ?: return DiscoverFeed()
        val data = runCatching {
            json.decodeFromString<AniListDiscoverResponse>(response).data
        }.getOrNull() ?: return DiscoverFeed()

        return DiscoverFeed(
            trending = data.trending?.media.orEmpty().map(::anilistWork),
            popular = data.popular?.media.orEmpty().map(::anilistWork),
            topRated = data.topRated?.media.orEmpty().map(::anilistWork),
        )
    }

    private fun anilistRecommendations(seedTitle: String): List<CatalogWork> {
        val response = anilistRequest(
            RECOMMENDATIONS_QUERY.replace("type: ANIME", "type: " + type.name),
            buildJsonObject { put("search", seedTitle) },
        ) ?: return emptyList()

        val seed = runCatching {
            json.decodeFromString<AniListResponse>(response).data?.Page?.media?.firstOrNull()
        }.getOrNull() ?: return emptyList()

        return seed.recommendations?.nodes.orEmpty()
            .mapNotNull { it.mediaRecommendation?.takeIf { media -> !media.isAdult } }
            .map(::anilistWork)
    }

    private fun anilistRequest(query: String, variables: JsonObject? = null): String? {
        val body = buildJsonObject {
            put("query", query)
            if (variables != null) {
                put("variables", variables)
            }
        }.toString()

        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return network.client.newCall(request).execute().use { result ->
            if (!result.isSuccessful) null else result.body.string()
        }
    }

    private fun anilistWork(item: AniListMedia): CatalogWork {
        val english = item.title?.english
        val romaji = item.title?.romaji
        val native = item.title?.native
        return CatalogWork(
            id = "anilist:${item.id}",
            title = english ?: romaji ?: native ?: "Sans titre",
            originalTitle = native ?: romaji,
            synonyms = item.synonyms,
            coverUrl = item.coverImage?.extraLarge ?: item.coverImage?.large,
            bannerUrl = item.bannerImage,
            synopsis = item.description?.let(::stripHtml),
            year = item.seasonYear,
            format = item.format,
            episodes = if (type == CatalogType.MANGA) {
                item.chapters
            } else {
                item.episodes
                    ?: item.nextAiringEpisode?.episode
                        ?.minus(1)
                        ?.takeIf { it > 0 }
            },
            genres = item.genres,
            providers = listOf("AniList"),
        )
    }

    // ------------------------------------------------------------------
    // Kitsu
    // ------------------------------------------------------------------

    private fun kitsu(query: String): List<CatalogWork> {
        val url = "https://kitsu.io/api/edge/" + type.slug + "?filter%5Btext%5D=${encode(query)}" +
            "&page%5Blimit%5D=10"
        val body = get(url, "application/vnd.api+json") ?: return emptyList()
        val items = json.decodeFromString<KitsuResponse>(body).data
        return items.mapNotNull { item ->
            val attributes = item.attributes ?: return@mapNotNull null
            CatalogWork(
                id = "kitsu:${item.id}",
                title = attributes.canonicalTitle ?: return@mapNotNull null,
                coverUrl = attributes.posterImage?.large ?: attributes.posterImage?.original,
                synopsis = attributes.synopsis?.let(::stripHtml),
                year = attributes.startDate?.take(4)?.toIntOrNull(),
                format = attributes.subtype?.uppercase(Locale.ROOT),
                episodes = attributes.episodeCount,
                providers = listOf("Kitsu"),
            )
        }
    }

    // ------------------------------------------------------------------
    // Jikan (MyAnimeList)
    // ------------------------------------------------------------------

    private fun jikan(query: String): List<CatalogWork> {
        val url = "https://api.jikan.moe/v4/" + type.slug + "?q=${encode(query)}&limit=10&sfw"
        val body = get(url, client = jikanClient) ?: return emptyList()
        val items = json.decodeFromString<JikanResponse>(body).data
        return items.mapNotNull { item ->
            val title = item.title_english ?: item.title ?: return@mapNotNull null
            CatalogWork(
                id = "mal:${item.mal_id ?: title}",
                title = title,
                originalTitle = item.title_japanese ?: item.title,
                coverUrl = item.images?.jpg?.large_image_url ?: item.images?.jpg?.image_url,
                synopsis = item.synopsis?.let(::stripHtml),
                year = item.year ?: item.aired?.prop?.from?.year,
                format = item.type,
                episodes = item.episodes,
                genres = item.genres.mapNotNull { it.name },
                providers = listOf("MAL"),
            )
        }
    }

    // ------------------------------------------------------------------
    // Fusion
    // ------------------------------------------------------------------

    private fun merge(items: List<CatalogWork>): List<CatalogWork> {
        val merged = mutableListOf<CatalogWork>()
        for (item in items) {
            val index = merged.indexOfFirst { existing -> sameWork(existing, item) }
            if (index == -1) {
                merged += item
            } else {
                val old = merged[index]
                merged[index] = old.copy(
                    title = old.title.ifBlank { item.title },
                    originalTitle = old.originalTitle ?: item.originalTitle,
                    synonyms = (old.synonyms + item.synonyms).distinct(),
                    coverUrl = old.coverUrl ?: item.coverUrl,
                    synopsis = old.synopsis ?: item.synopsis,
                    year = old.year ?: item.year,
                    format = old.format ?: item.format,
                    episodes = old.episodes ?: item.episodes,
                    genres = (old.genres + item.genres).distinct(),
                    providers = (old.providers + item.providers).distinct(),
                )
            }
        }
        return merged
    }

    private fun sameWork(a: CatalogWork, b: CatalogWork): Boolean {
        val left = normalizeTitle(a.title)
        val right = normalizeTitle(b.title)
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true

        val sameYear = a.year != null && a.year == b.year
        if (sameYear && (left.contains(right) || right.contains(left))) return true

        // AniList expose souvent le titre anglais alors que Kitsu/MAL
        // utilisent le titre original : on compare aussi les synonymes.
        val aAliases = titleAliases(a)
        val bAliases = titleAliases(b)
        val aliasMatch = right in aAliases || left in bAliases
        return aliasMatch && (a.year == null || b.year == null || sameYear)
    }

    private fun titleAliases(work: CatalogWork): Set<String> {
        return buildSet {
            add(normalizeTitle(work.title))
            work.originalTitle?.let { add(normalizeTitle(it)) }
            work.synonyms.forEach { add(normalizeTitle(it)) }
        }.filterNot { it.isBlank() }.toSet()
    }

    private fun normalizeTitle(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()
    }

    private fun stripHtml(value: String): String {
        return value
            .replace(BR_TAG, "\n")
            .replace(HTML_TAG, "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&#10;", "\n")
            .replace("&#13;", "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(NUMERIC_ENTITY, "")
            .trim()
    }

    private fun get(
        url: String,
        accept: String = "application/json",
        client: OkHttpClient = network.client,
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "Shiro/1.0")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ------------------------------------------------------------------
    // Modèles
    // ------------------------------------------------------------------

    @Serializable
    private data class AniListResponse(val data: AniListData? = null)

    @Serializable
    private data class AniListDiscoverResponse(val data: AniListDiscoverData? = null)

    @Serializable
    private data class AniListDiscoverData(
        val trending: AniListPage? = null,
        val popular: AniListPage? = null,
        val topRated: AniListPage? = null,
    )

    @Serializable
    private data class AniListData(val Page: AniListPage? = null)

    @Serializable
    private data class AniListPage(val media: List<AniListMedia> = emptyList())

    @Serializable
    private data class AniListMedia(
        val id: Int,
        val title: AniListTitle? = null,
        val synonyms: List<String> = emptyList(),
        val coverImage: AniListCover? = null,
        val bannerImage: String? = null,
        val description: String? = null,
        val episodes: Int? = null,
    val chapters: Int? = null,
        val nextAiringEpisode: AniListNextEpisode? = null,
        val format: String? = null,
        val seasonYear: Int? = null,
        val genres: List<String> = emptyList(),
        val isAdult: Boolean = false,
        val recommendations: AniListRecommendations? = null,
    )

    @Serializable
    private data class AniListRecommendations(
        val nodes: List<AniListRecommendationNode> = emptyList(),
    )

    @Serializable
    private data class AniListRecommendationNode(
        val mediaRecommendation: AniListMedia? = null,
    )

    @Serializable
    private data class AniListTitle(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
    )

    @Serializable
    private data class AniListCover(
        val extraLarge: String? = null,
        val large: String? = null,
    )

    @Serializable
    private data class AniListNextEpisode(val episode: Int? = null)

    @Serializable
    private data class KitsuResponse(val data: List<KitsuItem> = emptyList())

    @Serializable
    private data class KitsuItem(
        val id: String,
        val attributes: KitsuAttributes? = null,
    )

    @Serializable
    private data class KitsuAttributes(
        val canonicalTitle: String? = null,
        val posterImage: KitsuImage? = null,
        val synopsis: String? = null,
        val startDate: String? = null,
        val episodeCount: Int? = null,
        val subtype: String? = null,
    )

    @Serializable
    private data class KitsuImage(
        val large: String? = null,
        val original: String? = null,
    )

    @Serializable
    private data class JikanResponse(val data: List<JikanItem> = emptyList())

    @Serializable
    private data class JikanItem(
        val mal_id: Int? = null,
        val title: String? = null,
        val title_english: String? = null,
        val title_japanese: String? = null,
        val images: JikanImages? = null,
        val synopsis: String? = null,
        val year: Int? = null,
        val episodes: Int? = null,
    val chapters: Int? = null,
        val type: String? = null,
        val genres: List<JikanGenre> = emptyList(),
        val aired: JikanAired? = null,
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
    private data class JikanAired(val prop: JikanProp? = null)

    @Serializable
    private data class JikanProp(val from: JikanDate? = null)

    @Serializable
    private data class JikanDate(val year: Int? = null)

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 15_000L
        const val JIKAN_TIMEOUT_MS = 8_000L
        const val DISCOVER_TIMEOUT_MS = 15_000L
        const val RECOMMENDATIONS_TIMEOUT_MS = 15_000L
        const val FEED_CACHE_TTL_MS = 60 * 60 * 1000L
        const val RECOMMENDATIONS_CACHE_TTL_MS = 60 * 60 * 1000L

        // Partagé entre les instances du repository : changer d'onglet ne doit
        // pas relancer AniList si la page de découverte est recréée.
        val cacheLock = Any()
        var discoverCache: CachedDiscover? = null
        val recommendationsCache = mutableMapOf<String, CachedRecommendations>()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val HTML_TAG = Regex("""<[^>]+>""")
        val NUMERIC_ENTITY = Regex("&#\\d+;")

        const val ANILIST_QUERY = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 12) {
                media(search: ${'$'}search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) {
                  id
                  title { romaji english native }
                  synonyms
                  coverImage { extraLarge large }
                  description
                  episodes
                  chapters
                  nextAiringEpisode { episode }
                  format
                  seasonYear
                  genres
                }
              }
            }
        """

        const val DISCOVER_QUERY = """
            query {
              trending: Page(page: 1, perPage: 8) {
                media(type: ANIME, isAdult: false, sort: TRENDING_DESC) {
                  ...AniListWork
                }
              }
              popular: Page(page: 1, perPage: 8) {
                media(type: ANIME, isAdult: false, sort: POPULARITY_DESC) {
                  ...AniListWork
                }
              }
              topRated: Page(page: 1, perPage: 8) {
                media(type: ANIME, isAdult: false, sort: SCORE_DESC) {
                  ...AniListWork
                }
              }
            }
            fragment AniListWork on Media {
              id
              title { romaji english native }
              synonyms
              coverImage { extraLarge large }
              bannerImage
              description
              episodes
              chapters
              nextAiringEpisode { episode }
              format
              seasonYear
              genres
              isAdult
            }
        """

        const val RECOMMENDATIONS_QUERY = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 1) {
                media(search: ${'$'}search, type: ANIME, isAdult: false) {
                  recommendations(perPage: 8) {
                    nodes {
                      mediaRecommendation {
                        id
                        title { romaji english native }
                        synonyms
                        coverImage { extraLarge large }
                        bannerImage
                        description
                        episodes
                        chapters
                        nextAiringEpisode { episode }
                        format
                        seasonYear
                        genres
                        isAdult
                      }
                    }
                  }
                }
              }
            }
        """
    }
}
