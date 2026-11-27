package eu.kanade.tachiyomi.data.social.activity

import eu.kanade.tachiyomi.data.social.WorkSourceRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How the activity feed is assembled: what is kept, in which order, and what counts as a repeat. */
object ActivityFeedRules {

    private fun normalize(title: String) = title.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * [existing] and [incoming] merged, newest first, at most [max] items. The same event is kept
     * once. The user's own additions have two origins (the local record made when the work was
     * added, and the message read back from their activity room), so they are also taken for the
     * same thing when kind and title match: the newest one stays.
     */
    fun merge(
        existing: List<SocialActivityItem>,
        incoming: List<SocialActivityItem>,
        selfMxid: String?,
        max: Int,
    ): List<SocialActivityItem> =
        (existing + incoming)
            .sortedByDescending { it.timestamp }
            .distinctBy { if (it.actorMxid == selfMxid) "self|${it.kind}|${normalize(it.title)}" else it.eventId }
            .take(max)
}

/**
 * The feed kept on the phone, so it is there when the app starts and does not shrink to what the
 * Matrix rooms happen to return in a few seconds. One file per account.
 */
class ActivityFeedStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(
        val e: String,
        val a: String,
        val k: String,
        val t: String,
        val c: String? = null,
        val at: Long,
        val s: WorkSourceRef? = null,
    )

    private fun file(selfMxid: String) = File(dir, "activity_feed_" + selfMxid.hashCode().toUInt().toString(16) + ".json")

    @Synchronized
    fun load(selfMxid: String): List<SocialActivityItem> = runCatching {
        val f = file(selfMxid)
        if (!f.exists()) return emptyList()
        json.decodeFromString<List<Stored>>(f.readText()).mapNotNull { stored ->
            val kind = runCatching { SocialActivityKind.valueOf(stored.k) }.getOrNull() ?: return@mapNotNull null
            SocialActivityItem(stored.e, stored.a, kind, stored.t, stored.c, stored.at, stored.s)
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(selfMxid: String, items: List<SocialActivityItem>) {
        runCatching {
            dir.mkdirs()
            val stored = items.map { Stored(it.eventId, it.actorMxid, it.kind.name, it.title, it.coverUrl, it.timestamp, it.source) }
            val target = file(selfMxid)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(json.encodeToString(stored))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }
}
