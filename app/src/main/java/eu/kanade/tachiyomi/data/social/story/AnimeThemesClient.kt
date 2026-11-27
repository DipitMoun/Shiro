package eu.kanade.tachiyomi.data.social.story

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A theme the user can pick for a story. */
data class ThemeOption(
    val themeId: Long,
    /** "OP1", "ED2"… */
    val slug: String,
    val songTitle: String?,
    val artist: String?,
)

/** A theme ready to be played: where to stream it from. Never stored, never copied. */
data class ResolvedTheme(val streamUrl: String, val isAudioOnly: Boolean)

/** What AnimeThemes says about one theme. */
data class ThemeDetails(
    val slug: String?,
    val songTitle: String?,
    val artist: String?,
    val stream: ResolvedTheme?,
)

/**
 * AnimeThemes (https://animethemes.moe), read only. The app asks it for the themes of an anime
 * when a story is made, and again for the stream when a story is played: nothing of it is
 * downloaded, stored or re-hosted.
 *
 * The response shapes below follow the public API documentation; they could not be checked
 * against the live service while it was down, so every field is read defensively and any
 * failure ends in null (the viewer then says the extract cannot be loaded).
 */
class AnimeThemesClient(private val network: NetworkHelper) {

    /** Openings and endings of an anime, found through its AniList id. Null when the lookup fails. */
    suspend fun themesFor(anilistId: Long): List<ThemeOption>? = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("anime")
            .addQueryParameter("filter[has]", "resources")
            .addQueryParameter("filter[site]", "AniList")
            .addQueryParameter("filter[external_id]", anilistId.toString())
            .addQueryParameter("include", "animethemes.song.artists")
            .build()
        val root = fetch(url.toString()) ?: return@withContext null
        val anime = root["anime"]?.asArray()?.firstOrNull()?.asObject()
            ?: return@withContext emptyList()
        anime["animethemes"]?.asArray().orEmpty().mapNotNull { element ->
            val theme = element.asObject() ?: return@mapNotNull null
            val id = theme["id"]?.asLong() ?: return@mapNotNull null
            val song = theme["song"]?.asObject()
            ThemeOption(
                themeId = id,
                slug = theme["slug"]?.asString() ?: theme["type"]?.asString().orEmpty(),
                songTitle = song?.get("title")?.asString(),
                artist = song?.get("artists")?.asArray()?.firstOrNull()?.asObject()?.get("name")?.asString(),
            )
        }
    }

    /**
     * Title, artist and stream of a theme, the stream preferring the audio-only file. Null when
     * AnimeThemes does not answer: the caller then falls back to other sources for the card.
     */
    suspend fun fetchTheme(themeId: Long): ThemeDetails? = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("animetheme")
            .addPathSegment(themeId.toString())
            .addQueryParameter("include", "song.artists,animethemeentries.videos.audio")
            .build()
        val root = fetch(url.toString()) ?: return@withContext null
        val theme = root["animetheme"]?.asObject() ?: return@withContext null
        val song = theme["song"]?.asObject()
        val videos = theme["animethemeentries"]?.asArray().orEmpty()
            .flatMap { it.asObject()?.get("videos")?.asArray().orEmpty() }
            .mapNotNull { it.asObject() }
        val stream = videos.firstNotNullOfOrNull { video ->
            video["audio"]?.asObject()?.get("link")?.asString()?.takeIf { it.startsWith("http") }
                ?.let { ResolvedTheme(it, isAudioOnly = true) }
        } ?: videos.firstNotNullOfOrNull { video ->
            video["link"]?.asString()?.takeIf { it.startsWith("http") }?.let { ResolvedTheme(it, isAudioOnly = false) }
        }
        ThemeDetails(
            slug = theme["slug"]?.asString(),
            songTitle = song?.get("title")?.asString(),
            artist = song?.get("artists")?.asArray()?.firstOrNull()?.asObject()?.get("name")?.asString(),
            stream = stream,
        )
    }

    private fun fetch(url: String): JsonObject? = runCatching {
        network.client.newCall(GET(url)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            json.parseToJsonElement(response.body.string()) as? JsonObject
        }
    }.getOrNull()

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement.asString(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    private fun JsonElement.asLong(): Long? = runCatching { jsonPrimitive.longOrNull }.getOrNull()

    private companion object {
        const val BASE = "https://api.animethemes.moe"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
