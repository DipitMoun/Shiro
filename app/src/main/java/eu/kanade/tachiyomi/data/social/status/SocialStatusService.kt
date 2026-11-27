package eu.kanade.tachiyomi.data.social.status

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.session.SessionCredentialStore
import eu.kanade.tachiyomi.data.social.story.SocialStoryService
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.PUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.net.URLEncoder

/** Short text under the avatar. It lasts a day, like a story. */
object StatusRules {
    const val MAX_LENGTH = 60
    const val TTL_MS = 24L * 60 * 60 * 1000

    fun clean(text: String?): String = text.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH)

    fun isActive(atMillis: Long, now: Long): Boolean = now - atMillis in 0 until TTL_MS

    /** The text of a stored status, or null when blank or expired. */
    fun parse(json: String, now: Long): String? {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val text = clean(obj["text"]?.jsonPrimitive?.content)
        val at = obj["at"]?.jsonPrimitive?.longOrNull ?: return null
        return text.takeIf { it.isNotEmpty() && isActive(at, now) }
    }
}

/**
 * The status of the connected user and of the friends, and the writes of the own profile that the
 * Social page offers (status, display name).
 *
 * The status is a state event (`org.shiro.status`) in the story room of its owner: friends are in
 * that room, nobody else is. The SDK cannot read or write custom state events by name, so this
 * talks to the homeserver directly with the session of the connected account.
 */
class SocialStatusService(
    context: Context,
    private val session: MatrixSessionManager,
    private val credentials: SessionCredentialStore,
    private val network: NetworkHelper,
    private val stories: SocialStoryService,
    private val friends: SocialFriendStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("social_status", Context.MODE_PRIVATE)
    private val json = "application/json".toMediaType()

    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Active statuses by mxid, the user's own included. */
    val statuses: StateFlow<Map<String, String>> = _statuses.asStateFlow()

    /** Reads the statuses of the friends and of the user from their story rooms. */
    suspend fun refresh() {
        val self = selfMxid() ?: return
        val friendIds = friends.friends.value.mapTo(mutableSetOf()) { it.mxid } + self
        val rooms = stories.roomIds.value.filterKeys { it in friendIds }
        if (rooms.isEmpty()) return
        val now = System.currentTimeMillis()
        val read = coroutineScope {
            rooms.map { (owner, roomId) -> async { owner to readStatus(roomId, now) } }.awaitAll()
        }
        // A room that answers nothing keeps the previous value: a failed request is not "no status".
        val next = _statuses.value.toMutableMap()
        for ((owner, result) in read) {
            when (result) {
                is Read.Value -> next[owner] = result.text
                Read.Empty -> next.remove(owner)
                Read.Failed -> Unit
            }
        }
        _statuses.value = next
    }

    /** Publishes [text] as the status of the user, or clears it when blank. */
    suspend fun setStatus(text: String?): Boolean {
        val self = selfMxid() ?: return false
        val roomId = stories.ownRoomIdOrNull() ?: return false
        val clean = StatusRules.clean(text)
        val body = buildJsonObject {
            put("text", clean)
            put("at", System.currentTimeMillis())
        }.toString()
        val sent = put("/rooms/" + enc(roomId) + "/state/org.shiro.status", body)
        if (sent) {
            _statuses.value = _statuses.value.toMutableMap().also { if (clean.isEmpty()) it.remove(self) else it[self] = clean }
        }
        return sent
    }

    /** Changes the display name of the account. */
    suspend fun setDisplayName(name: String): Boolean {
        val self = selfMxid() ?: return false
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return false
        val body = buildJsonObject { put("displayname", clean) }.toString()
        return put("/profile/" + enc(self) + "/displayname", body)
    }

    private sealed interface Read {
        data class Value(val text: String) : Read
        data object Empty : Read
        data object Failed : Read
    }

    private suspend fun readStatus(roomId: String, now: Long): Read = withContext(Dispatchers.IO) {
        runCatching {
            val request = request("/rooms/" + enc(roomId) + "/state/org.shiro.status") ?: return@runCatching Read.Failed
            network.client.newCall(request.first).execute().use { response ->
                when {
                    response.code == 404 -> Read.Empty
                    !response.isSuccessful -> Read.Failed
                    else -> StatusRules.parse(response.body.string(), now)?.let { Read.Value(it) } ?: Read.Empty
                }
            }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "status read failed" }
            Read.Failed
        }
    }

    private suspend fun put(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (headers, base) = headersAndBase() ?: return@runCatching false
            network.client.newCall(PUT(base + path, headers, body.toRequestBody(json))).execute().use { it.isSuccessful }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "status write failed" }
            false
        }
    }

    private suspend fun request(path: String): Pair<okhttp3.Request, String>? {
        val (headers, base) = headersAndBase() ?: return null
        return GET(base + path, headers) to base
    }

    private suspend fun headersAndBase(): Pair<Headers, String>? {
        val stored = (credentials.load() as? SocialResult.Success)?.value ?: return null
        val headers = Headers.Builder().add("Authorization", "Bearer " + stored.accessToken).build()
        return headers to (stored.homeserverUrl.trimEnd('/') + "/_matrix/client/v3")
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun selfMxid(): String? = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId

    private companion object {
        const val MAX_NAME = 40
    }
}
