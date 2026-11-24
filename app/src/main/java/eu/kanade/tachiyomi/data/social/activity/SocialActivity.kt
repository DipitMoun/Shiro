package eu.kanade.tachiyomi.data.social.activity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SocialActivityKind(val msgtype: String) {
    ANIME_ADDED("org.shiro.activity.anime_added"),
    MANGA_ADDED("org.shiro.activity.manga_added"),
    ;

    companion object {
        fun fromMsgtype(msgtype: String): SocialActivityKind? = entries.firstOrNull { it.msgtype == msgtype }
    }
}

/** Something a friend did, e.g. adding a work to their library. */
data class SocialActivityItem(
    val eventId: String,
    val actorMxid: String,
    val kind: SocialActivityKind,
    val title: String,
    val coverUrl: String?,
    val timestamp: Long,
)

/**
 * Activity events travel as `m.room.message` with a custom msgtype in each user's private,
 * encrypted activity room (one per user, friends invited). The typed Matrix API only exposes
 * the msgtype and the body of such messages, so the payload is a small JSON body.
 */
object SocialActivityCodec {

    private const val TOPIC_PREFIX = "shiro:activity:"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(val t: String, val c: String? = null)

    fun topicFor(ownerMxid: String): String = TOPIC_PREFIX + ownerMxid

    /** Owner of an activity room, or null when [topic] is not one. */
    fun ownerOf(topic: String?): String? =
        topic?.takeIf { it.startsWith(TOPIC_PREFIX) }?.removePrefix(TOPIC_PREFIX)?.takeIf { it.startsWith("@") }

    fun isActivityMsgtype(msgtype: String): Boolean = SocialActivityKind.fromMsgtype(msgtype) != null

    fun encode(kind: SocialActivityKind, title: String, coverUrl: String?): String {
        val body = json.encodeToString(Payload.serializer(), Payload(t = title, c = coverUrl))
        return buildJsonObject {
            put("msgtype", kind.msgtype)
            put("body", body)
        }.toString()
    }

    fun decode(
        msgtype: String,
        body: String,
        eventId: String,
        senderMxid: String,
        timestamp: Long,
    ): SocialActivityItem? {
        val kind = SocialActivityKind.fromMsgtype(msgtype) ?: return null
        val payload = runCatching { json.decodeFromString(Payload.serializer(), body) }.getOrNull() ?: return null
        if (payload.t.isBlank()) return null
        return SocialActivityItem(
            eventId = eventId,
            actorMxid = senderMxid,
            kind = kind,
            title = payload.t,
            coverUrl = payload.c?.takeIf { it.startsWith("http") },
            timestamp = timestamp,
        )
    }
}
