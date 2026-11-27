package eu.kanade.tachiyomi.data.social.story

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** What a music story card shows. Every field can be missing; the card still has the label. */
data class ThemeMetadata(
    val title: String?,
    val artist: String?,
    val slug: String?,
    val coverUrl: String?,
)

/**
 * Everything needed to play a music story: the card, and the stream when it could be found.
 * [degraded] is true when AnimeThemes did not answer: the card then comes from the backup sources
 * and there is no extract, but the story is still shown.
 */
data class ThemePlayback(
    val metadata: ThemeMetadata,
    val streamUrl: String?,
    val degraded: Boolean,
)

/** Reads the texts Jikan gives for the openings and endings of an anime. */
object JikanThemeText {

    private val ENTRY = Regex("""^\s*#?\s*(\d+)?\s*:?\s*"(.+?)"\s+by\s+(.+?)(?:\s*\((?:eps?|episodes?)[^)]*\))?\s*$""", RegexOption.IGNORE_CASE)

    /** Title and artist of one entry such as `1: "Gurenge" by LiSA (eps 1-26)`. */
    fun parse(entry: String): Pair<String, String>? {
        val match = ENTRY.matchEntire(entry) ?: return null
        return match.groupValues[2].trim() to match.groupValues[3].trim()
    }

    /**
     * Title and artist for a slug such as `OP1` or `ED2` from Jikan's two lists, counting from 1.
     * A bare `OP` is the first opening.
     */
    fun pick(openings: List<String>, endings: List<String>, slug: String?): Pair<String, String>? {
        val match = Regex("""^(OP|ED)(\d*)$""", RegexOption.IGNORE_CASE).matchEntire(slug.orEmpty().trim()) ?: return null
        val list = if (match.groupValues[1].equals("OP", ignoreCase = true)) openings else endings
        val position = match.groupValues[2].toIntOrNull() ?: 1
        return list.getOrNull(position - 1)?.let(::parse)
    }
}

/**
 * Finds what a music story shows. AnimeThemes is the main source (title, artist and the stream).
 * When it does not answer, the artwork comes from AniList and the title and artist from Jikan, so
 * the extract is lost but the card stays. Nothing is stored or re-hosted.
 */
class ThemeResolver(
    private val network: NetworkHelper,
    private val animeThemes: AnimeThemesClient = AnimeThemesClient(network),
) {

    suspend fun resolve(ref: ThemeRef): ThemePlayback = coroutineScope {
        val details = async { withTimeoutOrNull(TIMEOUT_MS) { animeThemes.fetchTheme(ref.themeId) } }
        val media = async { withTimeoutOrNull(TIMEOUT_MS) { anilistMedia(ref.anilistId) } }
        val theme = details.await()
        val aniList = media.await()

        var title = theme?.songTitle
        var artist = theme?.artist
        if (title == null && ref.slug != null && aniList?.malId != null) {
            // AnimeThemes is down: the texts of Jikan, found by the slug (OP1, ED2…) kept in the story.
            withTimeoutOrNull(JIKAN_TIMEOUT_MS) { jikanThemes(aniList.malId) }?.let { (openings, endings) ->
                JikanThemeText.pick(openings, endings, ref.slug)?.let { (t, a) -> title = t; artist = a }
            }
        }
        ThemePlayback(
            metadata = ThemeMetadata(
                title = title,
                artist = artist,
                slug = theme?.slug ?: ref.slug,
                coverUrl = aniList?.coverUrl,
            ),
            streamUrl = theme?.stream?.streamUrl,
            degraded = theme == null,
        )
    }

    private class AniListMedia(val malId: Long?, val coverUrl: String?)

    private suspend fun anilistMedia(anilistId: Long): AniListMedia? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("query", "query(\$id:Int){Media(id:\$id){idMal coverImage{extraLarge large}}}")
            put("variables", buildJsonObject { put("id", anilistId) })
        }.toString().toRequestBody("application/json".toMediaType())
        runCatching {
            network.client.newCall(POST("https://graphql.anilist.co", body = body)).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val media = (JSON.parseToJsonElement(response.body.string()) as? JsonObject)
                    ?.get("data") as? JsonObject
                val m = media?.get("Media") as? JsonObject ?: return@use null
                val cover = m["coverImage"] as? JsonObject
                AniListMedia(
                    malId = m["idMal"]?.jsonPrimitive?.longOrNull,
                    coverUrl = (cover?.get("extraLarge") ?: cover?.get("large"))?.jsonPrimitive?.contentOrNull,
                )
            }
        }.getOrNull()
    }

    private suspend fun jikanThemes(malId: Long): Pair<List<String>, List<String>>? = withContext(Dispatchers.IO) {
        runCatching {
            network.client.newCall(GET("https://api.jikan.moe/v4/anime/$malId/themes")).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val data = (JSON.parseToJsonElement(response.body.string()) as? JsonObject)?.get("data") as? JsonObject
                    ?: return@use null
                fun texts(key: String) = (data[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                texts("openings") to texts("endings")
            }
        }.getOrNull()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val JIKAN_TIMEOUT_MS = 8_000L
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
