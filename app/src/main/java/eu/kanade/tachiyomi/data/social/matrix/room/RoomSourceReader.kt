package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.WorkSourceRef
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.session.SessionCredentialStore
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import java.net.URLEncoder

/**
 * Reads the `org.shiro.room` state of a work room to learn which extension and source its owner
 * uses for the work. The SDK cannot read custom state events by name, so this asks the homeserver
 * directly with the session of the connected account. Null on any problem: the caller falls back
 * to searching by title.
 */
class RoomSourceReader(
    private val credentials: SessionCredentialStore,
    private val network: NetworkHelper,
) {

    suspend fun read(roomId: String): WorkSourceRef? = withContext(Dispatchers.IO) {
        runCatching {
            val session = (credentials.load() as? SocialResult.Success)?.value ?: return@runCatching null
            val url = session.homeserverUrl.trimEnd('/') +
                "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, "UTF-8") + "/state/org.shiro.room"
            val headers = Headers.Builder().add("Authorization", "Bearer " + session.accessToken).build()
            network.client.newCall(GET(url, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = Json.parseToJsonElement(response.body.string()).jsonObject
                WorkSourceRef.decode(body["source"]?.toString())
            }
        }.getOrNull()
    }
}
