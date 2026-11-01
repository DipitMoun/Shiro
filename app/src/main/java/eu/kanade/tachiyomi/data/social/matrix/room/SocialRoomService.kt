package eu.kanade.tachiyomi.data.social.matrix.room

import eu.kanade.tachiyomi.data.social.audience.AudiencePlan
import eu.kanade.tachiyomi.data.social.audience.AudiencePolicy
import eu.kanade.tachiyomi.data.social.audience.SocialFriend
import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialSchemaKeys
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey

interface SocialRoomService {

    suspend fun ensureRoom(
        authorMxid: String,
        authorDisplayName: String?,
        selfMxid: String,
        work: WorkIdentity,
        friends: List<SocialFriend>,
    ): SocialResult<EnsuredRoom>

    suspend fun ensureThreadRoot(
        roomKey: SocialRoomKey,
        episodeKey: String,
        workTitle: String?,
    ): SocialResult<String>
}

data class EnsuredRoom(
    val roomId: String,
    val plan: AudiencePlan,
    val created: Boolean,
)

class DefaultSocialRoomService(
    private val transport: MatrixRoomTransport,
    private val bindings: RoomBindingStore,
) : SocialRoomService {

    override suspend fun ensureRoom(
        authorMxid: String,
        authorDisplayName: String?,
        selfMxid: String,
        work: WorkIdentity,
        friends: List<SocialFriend>,
    ): SocialResult<EnsuredRoom> {
        val roomKey = SocialRoomKey(authorMxid = authorMxid, workId = work.id)

        val cached = bindings.resolveRoom(roomKey)
        if (cached is SocialResult.Failure) return cached
        val existing = (cached as SocialResult.Success).value
        if (existing != null) {
            val plan = AudiencePolicy.plan(selfUserId = selfMxid, workId = work.id, friends = friends)
            return SocialResult.Success(EnsuredRoom(roomId = existing, plan = plan, created = false))
        }

        val plan = AudiencePolicy.plan(selfUserId = selfMxid, workId = work.id, friends = friends)

        val initialState = buildInitialState(roomKey, work, plan)
        val created = transport.createRoom(
            name = roomKey.roomName(work.title, authorDisplayName),
            topic = roomKey.roomTopic(),
            initialStateJson = initialState,
            invitees = plan.invited,
        )
        if (created is SocialResult.Failure) return created
        val roomId = (created as SocialResult.Success).value

        val bound = bindings.bindRoom(roomKey, roomId)
        if (bound is SocialResult.Failure) return bound

        return SocialResult.Success(EnsuredRoom(roomId = roomId, plan = plan, created = true))
    }

    override suspend fun ensureThreadRoot(
        roomKey: SocialRoomKey,
        episodeKey: String,
        workTitle: String?,
    ): SocialResult<String> {
        val parsedEpisode = EpisodeKey.parse(episodeKey)
            ?: return SocialResult.Failure(SocialError.Unknown("Invalid episodeKey: $episodeKey"))
        val threadKey = SocialThreadKey(room = roomKey, episodeKey = parsedEpisode)

        val cached = bindings.resolveThreadRoot(threadKey)
        if (cached is SocialResult.Failure) return cached
        val existing = (cached as SocialResult.Success).value
        if (existing != null) return SocialResult.Success(existing)

        val roomId = (bindings.resolveRoom(roomKey) as? SocialResult.Success)?.value
            ?: return SocialResult.Failure(
                SocialError.Unknown("Room for ${roomKey.value} is not bound; call ensureRoom first"),
            )

        val body = SocialSchemaKeys.threadRootBody(workTitle, roomKey.workId, threadKey.episodeKey)
        val contentJson = "{\"msgtype\":\"m.text\",\"body\":${jsonString(body)},\"org.shiro.thread\":{\"v\":1,\"workId\":${jsonString(roomKey.workId.value)},\"episodeKey\":${jsonString(threadKey.episodeKey.value)}}}"

        val sent = transport.sendMessage(
            roomId = roomId,
            contentJson = contentJson,
        )
        if (sent is SocialResult.Failure) return sent
        val rootEventId = (sent as SocialResult.Success).value

        val bound = bindings.bindThreadRoot(threadKey, rootEventId)
        if (bound is SocialResult.Failure) return bound

        return SocialResult.Success(rootEventId)
    }

    private fun buildInitialState(
        roomKey: SocialRoomKey,
        work: WorkIdentity,
        plan: AudiencePlan,
    ): String {
        val aliases = work.ids.entries.joinToString(",") { (k, v) ->
            "${jsonString(k)}:${jsonString(v)}"
        }
        val title = work.title?.let { jsonString(it) } ?: "null"
        return buildString {
            append("{")
            append("\"v\":1,")
            append("\"kind\":\"author-work\",")
            append("\"work\":{")
            append("\"id\":${jsonString(work.id.value)},")
            append("\"ids\":{$aliases},")
            append("\"title\":$title")
            append("},")
            append("\"owner\":${jsonString(roomKey.authorMxid)},")
            append("\"visibility\":\"invite\",")
            append("\"inviteReason\":${jsonString(plan.inviteReason)}")
            append("}")
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"'  -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
}