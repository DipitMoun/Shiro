package eu.kanade.tachiyomi.data.social.story

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.media.SocialMediaLoader
import eu.kanade.tachiyomi.data.social.matrix.room.MatrixRoomTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import tachiyomi.core.common.util.system.logcat
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.time.Duration
import java.util.Collections

/** A photo or a short video picked for a story, ready to be sent. */
class StoryUpload(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    /** Set for a video. */
    val durationMs: Long? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

sealed interface PublishResult {
    data object Sent : PublishResult

    /** The quota or the file size said no (or wants a confirmation): see the verdict. */
    data class Blocked(val verdict: SendVerdict) : PublishResult

    data class Failed(val message: String) : PublishResult
}

/** Story rooms are told apart from the others by their topic, like the activity rooms. */
object StoryRoomCodec {
    private const val TOPIC_PREFIX = "shiro:story:"

    fun topicFor(ownerMxid: String): String = TOPIC_PREFIX + ownerMxid

    fun ownerOf(topic: String?): String? =
        topic?.takeIf { it.startsWith(TOPIC_PREFIX) }?.removePrefix(TOPIC_PREFIX)?.takeIf { it.startsWith("@") }
}

/**
 * Stories of 24 hours: a photo, a short video, or a music extract (an AnimeThemes reference).
 *
 * Each user owns one story room (topic `shiro:story:<mxid>`) where friends are invited, as for the
 * activity room. A story is a message in that room; after 24 hours the app hides it and sends a
 * redaction to remove its content from the message. That does not delete the file from the
 * server, and the app never says it does.
 */
class SocialStoryService(
    private val context: Context,
    private val session: MatrixSessionManager,
    private val transport: MatrixRoomTransport,
    private val friends: SocialFriendStore,
    private val dispatcher: SocialCommandDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("social_story", Context.MODE_PRIVATE)
    private val roomMutex = Mutex()

    private val items = MutableStateFlow<List<StoryItem>>(emptyList())
    private val seen = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val _reels = MutableStateFlow<List<StoryReel>>(emptyList())

    /** Stories to show, own reel first, then the friends with something new. */
    val reels: StateFlow<List<StoryReel>> = _reels.asStateFlow()

    private val _quota = MutableStateFlow(StoryQuota.status(emptyList(), System.currentTimeMillis()))

    /** What was sent in the last 24 hours and 28 days. */
    val quota: StateFlow<QuotaStatus> = _quota.asStateFlow()

    @Volatile private var startedFor: String? = null

    @Volatile private var serverMaxBytes: Long? = null

    /** Idempotent per account. Called once the Matrix session is authenticated. */
    @Synchronized
    fun start() {
        val self = selfMxid() ?: return
        if (startedFor == self) return
        startedFor = self
        items.value = emptyList()
        seen.value = loadSeen(self)
        refreshQuota()

        scope.launch {
            // Keeps every current friend invited to our story room.
            friends.friends
                .map { list -> list.map { it.mxid }.toSet() }
                .distinctUntilChanged()
                .collect { mxids ->
                    runCatching { ensureOwnRoom(self, mxids) }
                        .onFailure { logcat(LogPriority.WARN, it) { "story room sync failed" } }
                }
        }
        scope.launch { refresh() }
    }

    // ------------------------------------------------------------------ reading

    /** Reloads our stories and our friends', hides what expired and removes our own expired ones. */
    suspend fun refresh() {
        val client = session.clientOrNull() ?: return
        val self = selfMxid() ?: return
        val friendIds = friends.friends.value.mapTo(mutableSetOf()) { it.mxid }
        val found = mutableListOf<StoryItem>()
        val ownRoomIds = mutableSetOf<String>()
        for (room in client.rooms()) {
            val owner = runCatching { StoryRoomCodec.ownerOf(room.topic()) }.getOrNull() ?: continue
            if (owner != self && owner !in friendIds) continue
            if (runCatching { room.membership() }.getOrNull() != Membership.JOINED) continue
            if (owner == self) ownRoomIds += room.id()
            found += readRecent(room)
        }
        items.value = found.distinctBy { it.eventId }
        rebuild()
        removeExpiredOwn(self, found.filter { it.authorMxid == self }, ownRoomIds.firstOrNull())
    }

    /** The bytes of a photo or a video story (a music story has none). */
    suspend fun loadMedia(item: StoryItem): ByteArray? {
        val media = item.content as? StoryContent.Media ?: return null
        val source = media.source as? org.matrix.rustcomponents.sdk.MediaSource ?: return null
        return runCatching { SocialMediaLoader.loadSource(item.eventId, source) }
            .onFailure { logcat(LogPriority.WARN, it) { "story media failed: ${item.eventId}" } }
            .getOrNull()
    }

    fun markSeen(authorMxid: String, upToMillis: Long) {
        val self = selfMxid() ?: return
        if ((seen.value[authorMxid] ?: 0L) >= upToMillis) return
        seen.value = seen.value + (authorMxid to upToMillis)
        prefs.edit { putLong("seen_${self}_$authorMxid", upToMillis) }
        rebuild()
    }

    // ------------------------------------------------------------------ publishing

    /** Whether a file of [bytes] can be sent now (quota, file size). */
    suspend fun verdictFor(bytes: Long): SendVerdict {
        val self = selfMxid() ?: return SendVerdict.Allowed
        return StoryQuota.verdict(
            records = loadRecords(self),
            now = System.currentTimeMillis(),
            bytes = bytes,
            maxFileBytes = maxFileBytes(),
        )
    }

    /** Smaller of the 10 MB rule and what the server accepts. */
    suspend fun maxFileBytes(): Long {
        val server = serverMaxBytes ?: runCatching {
            session.clientOrNull()?.getMaxMediaUploadSize()?.toLong()
        }.getOrNull()?.also { serverMaxBytes = it }
        return minOf(StoryLimits.DEFAULT_MAX_FILE_BYTES, server ?: Long.MAX_VALUE)
    }

    /** Sends a photo or a short video to our story room, if the quota allows it. */
    suspend fun publishMedia(upload: StoryUpload, caption: String?, confirmed: Boolean = false): PublishResult {
        val self = selfMxid() ?: return PublishResult.Failed("Pas de compte Matrix connecté")
        val verdict = verdictFor(upload.bytes.size.toLong())
        when (verdict) {
            is SendVerdict.FileTooBig, is SendVerdict.QuotaExceeded -> return PublishResult.Blocked(verdict)
            is SendVerdict.NeedsConfirmation -> if (!confirmed) return PublishResult.Blocked(verdict)
            SendVerdict.Allowed -> Unit
        }
        val client = session.clientOrNull() ?: return PublishResult.Failed("Pas de connexion Matrix")
        val roomId = ownRoomId(client, self) ?: return PublishResult.Failed("Salon des stories introuvable")
        val room = client.getRoom(roomId) ?: return PublishResult.Failed("Salon des stories introuvable")

        return try {
            val timeline = room.timelineWithConfiguration(liveConfig())
            try {
                val parameters = UploadParameters(
                    source = UploadSource.Data(upload.bytes, upload.filename),
                    caption = caption?.trim()?.takeIf { it.isNotEmpty() },
                    formattedCaption = null,
                    mentions = null,
                    inReplyTo = null,
                    extraContentJson = null,
                )
                if (upload.isVideo) {
                    val info = VideoInfo(
                        Duration.ofMillis(upload.durationMs ?: 0L),
                        upload.height.toULong(),
                        upload.width.toULong(),
                        upload.mimeType,
                        upload.bytes.size.toULong(),
                        null,
                        null,
                        null,
                    )
                    timeline.sendVideo(parameters, null, info).join()
                } else {
                    val info = ImageInfo(
                        upload.height.toULong(),
                        upload.width.toULong(),
                        upload.mimeType,
                        upload.bytes.size.toULong(),
                        null,
                        null,
                        null,
                        upload.mimeType.equals("image/gif", ignoreCase = true),
                    )
                    timeline.sendImage(parameters, null, info).join()
                }
            } finally {
                runCatching { timeline.close() }
            }
            addRecord(self, SentRecord(System.currentTimeMillis(), upload.bytes.size.toLong()))
            scope.launch { refresh() }
            PublishResult.Sent
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "story publish failed" }
            PublishResult.Failed(t.message ?: "Envoi impossible")
        }
    }

    /** Shares an extract of a theme: a few bytes of reference, nothing counted against the quota. */
    suspend fun publishMusic(theme: ThemeRef): PublishResult {
        val self = selfMxid() ?: return PublishResult.Failed("Pas de compte Matrix connecté")
        val client = session.clientOrNull() ?: return PublishResult.Failed("Pas de connexion Matrix")
        val roomId = ownRoomId(client, self) ?: return PublishResult.Failed("Salon des stories introuvable")
        val content = kotlinx.serialization.json.buildJsonObject {
            put("msgtype", kotlinx.serialization.json.JsonPrimitive(ThemeRef.MSGTYPE))
            put("body", kotlinx.serialization.json.JsonPrimitive(theme.encode()))
        }.toString()
        return when (val sent = transport.sendMessage(roomId, content)) {
            is SocialResult.Success -> {
                scope.launch { refresh() }
                PublishResult.Sent
            }
            is SocialResult.Failure -> PublishResult.Failed(sent.error.toString())
        }
    }

    /** Removes one of our stories now (the "Supprimer" of the viewer). */
    suspend fun delete(item: StoryItem): Boolean {
        val self = selfMxid() ?: return false
        if (item.authorMxid != self) return false
        val client = session.clientOrNull() ?: return false
        val roomId = ownRoomId(client, self) ?: return false
        val result = dispatcher.execute(SocialCommand.RedactInRoom(roomId, item.eventId, "story deleted"))
        if (result is SocialResult.Failure) return false
        items.value = items.value.filterNot { it.eventId == item.eventId }
        rebuild()
        return true
    }

    // ------------------------------------------------------------------ quota

    fun quotaRecords(): List<SentRecord> = selfMxid()?.let(::loadRecords).orEmpty()

    /** Approximate time at which the first of the recent sends stops counting, null when none does. */
    fun nextQuotaReturn(now: Long = System.currentTimeMillis()): Long? {
        val records = quotaRecords()
        val day = records.filter { now - it.atMillis < StoryLimits.DAY_MS }.minOfOrNull { it.atMillis + StoryLimits.DAY_MS }
        val month = records.filter { now - it.atMillis < StoryLimits.MONTH_MS }.minOfOrNull { it.atMillis + StoryLimits.MONTH_MS }
        return listOfNotNull(day, month).minOrNull()
    }

    // ------------------------------------------------------------------ internals

    private fun rebuild() {
        val self = selfMxid() ?: return
        _reels.value = StoryReels.build(items.value, System.currentTimeMillis(), seen.value, self)
    }

    private fun refreshQuota() {
        val self = selfMxid() ?: return
        _quota.value = StoryQuota.status(loadRecords(self), System.currentTimeMillis())
    }

    private fun loadRecords(self: String): List<SentRecord> {
        val raw = prefs.getString("quota_$self", "").orEmpty()
        val cutoff = System.currentTimeMillis() - StoryLimits.MONTH_MS
        return raw.split(',').mapNotNull { part ->
            val pieces = part.split(':')
            val at = pieces.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val bytes = pieces.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            SentRecord(at, bytes).takeIf { at >= cutoff }
        }
    }

    private fun addRecord(self: String, record: SentRecord) {
        val all = loadRecords(self) + record
        prefs.edit { putString("quota_$self", all.joinToString(",") { "${it.atMillis}:${it.bytes}" }) }
        refreshQuota()
    }

    private fun loadSeen(self: String): Map<String, Long> {
        val prefix = "seen_${self}_"
        return prefs.all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (key, value) -> (value as? Long)?.let { key.removePrefix(prefix) to it } }
            .toMap()
    }

    /** Own stories older than 24 hours: redacted once, and remembered so it is not repeated. */
    private suspend fun removeExpiredOwn(self: String, own: List<StoryItem>, roomId: String?) {
        roomId ?: return
        val now = System.currentTimeMillis()
        val done = prefs.getStringSet("redacted_$self", emptySet()).orEmpty().toMutableSet()
        val expired = own.filter { !StoryExpiry.isActive(it.postedAtMillis, now) && it.eventId !in done }
        for (item in expired) {
            val result = dispatcher.execute(SocialCommand.RedactInRoom(roomId, item.eventId, "story expired"))
            if (result is SocialResult.Success) done += item.eventId
        }
        if (expired.isNotEmpty()) prefs.edit { putStringSet("redacted_$self", done.toList().takeLast(200).toSet()) }
    }

    private suspend fun ensureOwnRoom(self: String, friendIds: Set<String>) = roomMutex.withLock {
        val client = session.clientOrNull() ?: return@withLock
        var roomId = ownRoomId(client, self)
        if (roomId == null) {
            val created = transport.createRoom(
                name = "Stories Shiro",
                topic = StoryRoomCodec.topicFor(self),
                initialStateJson = """{"kind":"story"}""",
                invitees = friendIds.toList(),
            )
            roomId = (created as? SocialResult.Success)?.value ?: return@withLock
            logcat(LogPriority.INFO) { "story room created" }
        }
        prefs.edit { putString("own_room_$self", roomId) }

        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return@withLock
        for (friend in friendIds) {
            val membership = runCatching { room.member(friend).membership }.getOrNull()
            if (membership is MembershipState.Join || membership is MembershipState.Invite) continue
            transport.invite(roomId, friend)
        }
    }

    private fun ownRoomId(client: Client, self: String): String? =
        prefs.getString("own_room_$self", null)?.takeIf { isJoined(client, it) }
            ?: client.rooms().firstOrNull { room ->
                runCatching {
                    room.topic() == StoryRoomCodec.topicFor(self) && room.membership() == Membership.JOINED
                }.getOrDefault(false)
            }?.id()

    private fun isJoined(client: Client, roomId: String): Boolean =
        runCatching { client.getRoom(roomId)?.membership() == Membership.JOINED }.getOrDefault(false)

    private fun liveConfig() = TimelineConfiguration(
        focus = TimelineFocus.Live(hideThreadedEvents = false),
        filter = TimelineFilter.All,
        internalIdPrefix = "",
        dateDividerMode = DateDividerMode.DAILY,
        trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
        reportUtds = false,
    )

    /** Last messages of a story room, read through a short-lived timeline. */
    private suspend fun readRecent(room: Room): List<StoryItem> {
        val timeline = runCatching { room.timelineWithConfiguration(liveConfig()) }.getOrNull() ?: return emptyList()
        val collected = Collections.synchronizedList(mutableListOf<TimelineItem>())
        val handle = timeline.addListener(
            object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diff.forEach { collected += itemsOf(it) }
                }
            },
        )
        try {
            // SDK calls ignore cancellation: bound the wait, not the work.
            val paging = scope.async { runCatching { timeline.paginateBackwards(PAGE.toUShort()) } }
            withTimeoutOrNull(PAGINATE_TIMEOUT_MS) { paging.await() }
            delay(SETTLE_MS)
        } finally {
            runCatching { handle.cancel() }
            runCatching { timeline.close() }
        }
        return synchronized(collected) { collected.toList() }.mapNotNull(::toStory).distinctBy { it.eventId }
    }

    private fun itemsOf(diff: TimelineDiff): List<TimelineItem> = when (diff) {
        is TimelineDiff.PushBack -> listOf(diff.value)
        is TimelineDiff.PushFront -> listOf(diff.value)
        is TimelineDiff.Insert -> listOf(diff.value)
        is TimelineDiff.Set -> listOf(diff.value)
        is TimelineDiff.Append -> diff.values
        is TimelineDiff.Reset -> diff.values
        else -> emptyList()
    }

    private fun toStory(item: TimelineItem): StoryItem? {
        val event = item.asEvent() ?: return null
        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return null
        val msgLike = event.content as? TimelineItemContent.MsgLike ?: return null
        val message = msgLike.content.kind as? MsgLikeKind.Message ?: return null
        val posted = event.timestamp.toLong()

        val content: StoryContent
        val caption: String?
        when (val type = message.content.msgType) {
            is MessageType.Image -> {
                val c = type.content
                val info = c.info
                caption = c.caption
                content = StoryContent.Media(
                    isVideo = false,
                    source = c.source,
                    mimeType = info?.mimetype,
                    width = info?.width?.toInt(),
                    height = info?.height?.toInt(),
                    durationMs = null,
                    sizeBytes = info?.size?.toLong(),
                )
            }
            is MessageType.Video -> {
                val c = type.content
                val info = c.info
                caption = c.caption
                content = StoryContent.Media(
                    isVideo = true,
                    source = c.source,
                    mimeType = info?.mimetype,
                    width = info?.width?.toInt(),
                    height = info?.height?.toInt(),
                    durationMs = info?.duration?.toMillis(),
                    sizeBytes = info?.size?.toLong(),
                )
            }
            is MessageType.Other -> {
                if (type.msgtype != ThemeRef.MSGTYPE) return null
                caption = null
                content = StoryContent.Music(ThemeRef.decode(type.body) ?: return null)
            }
            else -> return null
        }
        return StoryItem(eventId, event.sender, posted, caption, content)
    }

    private fun selfMxid(): String? = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId

    private companion object {
        const val PAGE = 40
        const val PAGINATE_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 400L
    }
}
