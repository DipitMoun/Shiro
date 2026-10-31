package eu.kanade.tachiyomi.data.telegram.index

import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat

/**
 * Résout une œuvre Telegram vers une entrée AniList.
 *
 * Quatre niveaux :
 * 1. cache persistant (titre normalisé + saison + type) ;
 * 2. recherche AniList sur le titre de la saison ;
 * 3. recherche AniList sur le titre de base si la première échoue ;
 * 4. score minimal + marge sur le deuxième candidat, sinon « non reconnu ».
 *
 * Les candidats sont conservés pour permettre une correction manuelle future.
 */
class TelegramWorkResolver(
    private val store: TelegramIndexStore,
    private val network: NetworkHelper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private var cache: MutableMap<String, TelegramResolvedWork>? = null
    private var lastRequestAt = 0L

    suspend fun resolve(
        title: String,
        season: Int?,
        kind: TelegramMediaKind,
        forcedTitle: String? = null,
        acceptAny: Boolean = false,
    ): TelegramResolvedWork = mutex.withLock {
        val base = forcedTitle?.takeIf { it.isNotBlank() } ?: title
        val query = base.trim()
        if (query.isBlank()) {
            return@withLock TelegramResolvedWork(query = "")
        }

        val key = cacheKey(query, season, kind)
        val cached = loadCache()[key]
        if (cached != null && isFresh(cached)) {
            // Reconnaissance manuelle : si l'utilisateur a explicitement demandé
            // AniList, le meilleur candidat reste sélectionnable même sous le
            // seuil automatique.
            if (acceptAny && cached.anilistId == null && cached.candidates.isNotEmpty()) {
                val candidate = cached.candidates.first()
                return@withLock cached.copy(
                    anilistId = candidate.anilistId,
                    title = candidate.title,
                    coverUrl = candidate.coverUrl,
                    year = candidate.year,
                    score = candidate.score,
                    provider = "ANILIST",
                    resolvedAt = System.currentTimeMillis(),
                )
            }
            return@withLock cached
        }

        val searchTitle = if (!forcedTitle.isNullOrBlank() || season == null || season < 2) {
            query
        } else {
            "$query Season $season"
        }

        val type = if (kind == TelegramMediaKind.VIDEO) "ANIME" else "MANGA"

        val firstSearch = runCatching { search(searchTitle, type) }
        if (firstSearch.isFailure) {
            logcat(LogPriority.WARN, firstSearch.exceptionOrNull()) {
                "AniList indisponible pour « $query »"
            }
            return@withLock TelegramResolvedWork(query = query, title = query, failed = true)
        }
        val candidates = firstSearch.getOrDefault(emptyList())

        val fallback = buildList {
            if (candidates.none { score(query, season, kind, it) >= ACCEPT_SCORE }) {
                if (searchTitle != query) {
                    addAll(runCatching { search(query, type) }.getOrDefault(emptyList()))
                }
                // « Kagura Bachi » -> « KaguraBachi ».
                val compact = query.replace(" ", "")
                if (compact != query && compact.isNotBlank()) {
                    addAll(runCatching { search(compact, type) }.getOrDefault(emptyList()))
                }
            }
        }

        val pool = (candidates + fallback).distinctBy { it.id }
        val scored = pool
            .map { media -> media to score(query, season, kind, media) }
            .sortedWith(
                compareByDescending<Pair<AniListMedia, Double>> { it.second }
                    .thenByDescending { formatRank(it.first, kind) },
            )

        val best = scored.firstOrNull()
        val bestTitle = best?.first?.bestTitle()?.let(::normalize)

        // Une série TV et un film homonymes ne sont pas une ambiguïté : le
        // classement par format a déjà tranché.
        val runnerUp = scored.drop(1).firstOrNull { (media, _) ->
            normalize(media.bestTitle()) != bestTitle
        }

        val accepted = best != null &&
            (best.second >= ACCEPT_SCORE || acceptAny) &&
            (runnerUp == null || best.second - runnerUp.second >= MIN_MARGIN || acceptAny)

        val result = if (accepted) {
            val media = best!!.first
            TelegramResolvedWork(
                query = query,
                anilistId = media.id,
                title = media.bestTitle(),
                coverUrl = media.coverImage?.large,
                description = media.description?.let(::stripHtml),
                genres = media.genres,
                year = media.seasonYear,
                totalEpisodes = media.episodes,
                status = media.status,
                score = best.second,
                candidates = candidatesOf(scored),
                resolvedAt = System.currentTimeMillis(),
            )
        } else {
            TelegramResolvedWork(
                query = query,
                title = query,
                score = best?.second ?: 0.0,
                candidates = candidatesOf(scored),
                resolvedAt = System.currentTimeMillis(),
            )
        }

        val updated = loadCache().toMutableMap()
        updated[key] = result
        cache = updated
        store.writeResolveCache(updated)
        result
    }

    private fun candidatesOf(scored: List<Pair<AniListMedia, Double>>): List<TelegramWorkCandidate> {
        return scored.take(MAX_CANDIDATES).map { (media, value) ->
            TelegramWorkCandidate(
                anilistId = media.id,
                title = media.bestTitle(),
                score = value,
                year = media.seasonYear,
                coverUrl = media.coverImage?.large,
            )
        }
    }

    private fun isFresh(work: TelegramResolvedWork): Boolean {
        if (work.resolvedAt == 0L) return false
        val age = System.currentTimeMillis() - work.resolvedAt
        val ttl = if (work.anilistId != null) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
        return age in 0..ttl
    }

    private fun loadCache(): Map<String, TelegramResolvedWork> {
        return cache ?: store.readResolveCache().also { cache = it.toMutableMap() }
    }

    private fun cacheKey(title: String, season: Int?, kind: TelegramMediaKind): String {
        return "v$RESOLVER_VERSION|${TelegramTitleParser.slug(title)}|${season ?: 0}|${kind.name}"
    }

    /** Départage à score égal : une série TV prime sur un film homonyme. */
    private fun formatRank(media: AniListMedia, kind: TelegramMediaKind): Int = when (kind) {
        TelegramMediaKind.VIDEO -> when (media.format) {
            "TV" -> 5
            "TV_SHORT" -> 4
            "ONA" -> 3
            "MOVIE" -> 2
            "OVA", "SPECIAL" -> 1
            else -> 0
        }
        TelegramMediaKind.DOCUMENT -> when (media.format) {
            "MANGA" -> 3
            "ONE_SHOT" -> 2
            else -> 0
        }
    }

    // ------------------------------------------------------------------
    // AniList
    // ------------------------------------------------------------------

    private suspend fun search(search: String, type: String): List<AniListMedia> = withContext(Dispatchers.IO) {
        throttle()

        val body = buildJsonObject {
            put("query", QUERY)
            put(
                "variables",
                buildJsonObject {
                    put("search", search)
                    put("type", type)
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(API_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("AniList HTTP ${response.code}")
            }
            val payload = response.body.string()
            val parsed = json.decodeFromString<AniListResponse>(payload)
            parsed.data?.Page?.media.orEmpty()
        }.also {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private suspend fun throttle() {
        val elapsed = System.currentTimeMillis() - lastRequestAt
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - elapsed)
        }
    }

    private fun score(
        query: String,
        season: Int?,
        kind: TelegramMediaKind,
        media: AniListMedia,
    ): Double {
        val normalizedQuery = normalize(query)
        val primaryNames = listOfNotNull(
            media.title.romaji,
            media.title.english,
            media.title.native,
        )
        val primaryScore = primaryNames.maxOfOrNull { similarity(normalizedQuery, normalize(it)) } ?: 0.0
        val synonymScore = media.synonyms.maxOfOrNull { similarity(normalizedQuery, normalize(it)) } ?: 0.0

        // Un synonyme exact (« Petit Clover Advance » -> « Black Clover ») ne doit
        // pas égaler un vrai titre : il est légèrement pondéré.
        var value = maxOf(primaryScore, synonymScore * SYNONYM_WEIGHT)

        if (season != null) {
            val candidateSeason = detectSeason(media.allTitles())
            value += when {
                candidateSeason == season -> 0.08
                candidateSeason != null -> -0.12
                else -> 0.0
            }
        }

        value += when (kind) {
            TelegramMediaKind.VIDEO -> when (media.format) {
                "TV", "TV_SHORT", "ONA", "MOVIE", "OVA", "SPECIAL" -> 0.04
                else -> -0.10
            }
            TelegramMediaKind.DOCUMENT -> when (media.format) {
                "MANGA", "ONE_SHOT" -> 0.04
                else -> -0.10
            }
        }

        return value.coerceIn(0.0, 1.0)
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        // « Kagura Bachi » et « Kagurabachi », « Blue Lock » et « Bluelock ».
        if (a.replace(" ", "") == b.replace(" ", "")) return 0.95

        val aTokens = a.split(' ').filter { it.isNotBlank() }.toSet()
        val bTokens = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0

        val intersection = aTokens.intersect(bTokens).size
        val union = aTokens.union(bTokens).size
        val jaccard = intersection.toDouble() / union
        val containment = if (a.contains(b) || b.contains(a)) 0.12 else 0.0

        return (jaccard * 0.88 + containment).coerceAtMost(0.99)
    }

    private fun normalize(value: String): String {
        return TelegramTitleParser.slug(value).replace('-', ' ')
    }

    private fun detectSeason(title: String): Int? {
        SEASON_PATTERN.find(title)?.let { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun stripHtml(value: String): String {
        return value
            .replace(BR_TAG, "\n")
            .replace(HTML_TAG, "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    // ------------------------------------------------------------------
    // Modèles AniList
    // ------------------------------------------------------------------

    @Serializable
    private data class AniListResponse(val data: AniListData? = null)

    @Serializable
    private data class AniListData(val Page: AniListPage? = null)

    @Serializable
    private data class AniListPage(val media: List<AniListMedia> = emptyList())

    @Serializable
    private data class AniListMedia(
        val id: Int,
        val title: AniListTitle = AniListTitle(),
        val synonyms: List<String> = emptyList(),
        val format: String? = null,
        val episodes: Int? = null,
        val seasonYear: Int? = null,
        val coverImage: AniListCover? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val status: String? = null,
    ) {
        fun allTitles(): String = listOfNotNull(
            title.romaji,
            title.english,
            title.native,
        ).joinToString(" ")

        fun bestTitle(): String = title.english?.takeIf { it.isNotBlank() }
            ?: title.romaji?.takeIf { it.isNotBlank() }
            ?: title.native.orEmpty()
    }

    @Serializable
    private data class AniListTitle(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
    )

    @Serializable
    private data class AniListCover(val large: String? = null)

    private companion object {
        const val API_URL = "https://graphql.anilist.co"
        const val ACCEPT_SCORE = 0.72
        const val MIN_MARGIN = 0.05
        const val MAX_CANDIDATES = 3
        const val MIN_REQUEST_INTERVAL_MS = 350L
        const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000

        /** À incrémenter quand le scoring change : invalide les anciens résultats. */
        const val RESOLVER_VERSION = 4

        /** Un synonyme exact reste sous un titre principal exact. */
        const val SYNONYM_WEIGHT = 0.9
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        val QUERY = """
            query (${'$'}search: String, ${'$'}type: MediaType) {
              Page(perPage: 6) {
                media(search: ${'$'}search, type: ${'$'}type, sort: SEARCH_MATCH) {
                  id
                  title { romaji english native }
                  synonyms
                  format
                  episodes
                  seasonYear
                  status
                  coverImage { large }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        val BR_TAG = Regex("""(?i)<br\s*/?>""")
        val HTML_TAG = Regex("""<[^>]+>""")
        val SEASON_PATTERN = Regex(
            """(?i)\b(?:saison|season)\s*(\d{1,2})\b|\b(\d{1,2})(?:st|nd|rd|th)\s+season\b""",
        )
    }
}
