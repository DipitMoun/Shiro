package eu.kanade.tachiyomi.data.social.activity

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.matrix.room.MatrixRoomTransport
import eu.kanade.tachiyomi.data.social.matrix.sync.SocialNotificationCursor
import eu.kanade.tachiyomi.ui.social.SocialNotificationHelper
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
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.manga.interactor.GetLibraryManga
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.util.Collections

/**
 * Social activity: what friends add to their library, and sharing ours.
 *
 * Each user owns one private, encrypted activity room (topic `shiro:activity:<mxid>`) where
 * friends are invited. Library additions are posted there; friends' rooms are read for the
 * feed and watched live for "your friend added X, you might like it" notifications.
 */
class SocialActivityService(
    private val context: Context,
    private val session: MatrixSessionManager,
    private val transport: MatrixRoomTransport,
    private val friends: SocialFriendStore,
    private val getLibraryAnime: GetLibraryAnime,
    private val getLibraryManga: GetLibraryManga,
    private val cursor: SocialNotificationCursor,
    private val avatarLoader: SocialAvatarLoader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("social_activity", Context.MODE_PRIVATE)
    private val roomMutex = Mutex()
    private val store = ActivityFeedStore(java.io.File(context.filesDir, "social"))

    private val _feed = MutableStateFlow<List<SocialActivityItem>>(emptyList())

    /** The user's own activity and the friends', newest first, kept on the phone. */
    val feed: StateFlow<List<SocialActivityItem>> = _feed.asStateFlow()

    private val _shareEnabled = MutableStateFlow(prefs.getBoolean(KEY_SHARE, true))
    val shareEnabled: StateFlow<Boolean> = _shareEnabled.asStateFlow()

    @Volatile private var startedFor: String? = null

    @Volatile private var libraryTitles: Set<String> = emptySet()

    fun setShareEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHARE, enabled) }
        _shareEnabled.value = enabled
    }

    /** Idempotent per account. Called once the Matrix session is authenticated. */
    @Synchronized
    fun start() {
        val self = selfMxid() ?: return
        if (startedFor == self) return
        startedFor = self
        // What was seen before is shown at once; the rooms then add what is new.
        _feed.value = store.load(self)

        scope.launch {
            // Keeps every current friend invited to our activity room.
            friends.friends
                .map { list -> list.map { it.mxid }.toSet() }
                .distinctUntilChanged()
                .collect { mxids ->
                    runCatching { ensureOwnRoom(self, mxids) }
                        .onFailure { logcat(LogPriority.WARN, it) { "activity room sync failed" } }
                }
        }
        scope.launch {
            getLibraryAnime.subscribe()
                .map { list -> list.map { it.anime }.distinctBy { it.id } }
                .collect { animes ->
                    publishAdditions(
                        kind = SocialActivityKind.ANIME_ADDED,
                        current = animes.associate {
                            it.id to AddedWork(it.title, it.thumbnailUrl, eu.kanade.tachiyomi.data.social.WorkSourceRefs.forAnime(it))
                        },
                    )
                }
        }
        scope.launch {
            getLibraryManga.subscribe()
                .map { list -> list.map { it.manga }.distinctBy { it.id } }
                .collect { mangas ->
                    publishAdditions(
                        kind = SocialActivityKind.MANGA_ADDED,
                        current = mangas.associate {
                            it.id to AddedWork(it.title, it.thumbnailUrl, eu.kanade.tachiyomi.data.social.WorkSourceRefs.forManga(it))
                        },
                    )
                }
        }
        scope.launch { refreshFeed() }
    }

    /** Reloads friends' recent activity from their activity rooms. */
    suspend fun refreshFeed() {
        val client = session.clientOrNull() ?: return
        val self = selfMxid() ?: return
        val friendIds = friends.friends.value.mapTo(mutableSetOf()) { it.mxid }
        val items = mutableListOf<SocialActivityItem>()
        for (room in client.rooms()) {
            val owner = runCatching { SocialActivityCodec.ownerOf(room.topic()) }.getOrNull() ?: continue
            // Our own room too: it holds what we added on a previous install or another phone.
            if (owner != self && owner !in friendIds) continue
            if (runCatching { room.membership() }.getOrNull() != Membership.JOINED) continue
            items += readRecent(room)
        }
        merge(items)
    }

    /** Activity of one friend, from the current feed. */
    fun activityOf(mxid: String): List<SocialActivityItem> = _feed.value.filter { it.actorMxid == mxid }

    fun isActivityRoom(room: Room): Boolean =
        runCatching { SocialActivityCodec.ownerOf(room.topic()) }.getOrNull() != null

    /** Live timeline item from a friend's activity room (see SocialLiveListener). */
    fun onTimelineItem(room: Room, item: TimelineItem) {
        val activity = toActivity(item) ?: return
        val owner = runCatching { SocialActivityCodec.ownerOf(room.topic()) }.getOrNull() ?: return
        if (owner == selfMxid()) return
        onIncoming(room.id(), activity)
    }

    /** Activity carried by a push (see ShiroMessagingService). */
    fun onPushed(roomId: String, eventId: String, senderMxid: String, msgtype: String, body: String, timestamp: Long) {
        if (senderMxid == selfMxid()) return
        val activity = SocialActivityCodec.decode(msgtype, body, eventId, senderMxid, timestamp) ?: return
        onIncoming(roomId, activity)
    }

    private fun onIncoming(roomId: String, activity: SocialActivityItem) {
        merge(listOf(activity))
        if (!cursor.tryClaim(roomId, activity.eventId, activity.timestamp)) return
        if (isInLibrary(activity.title)) return
        scope.launch {
            val actorName = friends.friends.value.firstOrNull { it.mxid == activity.actorMxid }?.displayName
                ?: activity.actorMxid.substringBefore(':').removePrefix("@")
            val avatar = runCatching { avatarLoader.loadProfileAvatar(activity.actorMxid, 128) }.getOrNull()
            val banner = eu.kanade.tachiyomi.ui.social.ActivityCoverBanner.load(activity.coverUrl)
            SocialNotificationHelper.showActivity(context, activity, actorName, avatar, banner)
        }
    }

    fun isInLibrary(title: String): Boolean = normalize(title) in libraryTitles

    private fun merge(items: List<SocialActivityItem>) {
        if (items.isEmpty()) return
        val self = selfMxid()
        val merged = synchronized(this) {
            ActivityFeedRules.merge(_feed.value, items, self, MAX_FEED).also { _feed.value = it }
        }
        if (self != null) store.save(self, merged)
    }

    private class AddedWork(val title: String, val cover: String?, val source: eu.kanade.tachiyomi.data.social.WorkSourceRef?)

    private suspend fun publishAdditions(kind: SocialActivityKind, current: Map<Long, AddedWork>) {
        val self = selfMxid() ?: return
        libraryTitles = libraryTitles + current.values.map { normalize(it.title) }

        val key = "known_${kind.name}_$self"
        val known = prefs.getStringSet(key, null)
        val currentIds = current.keys.mapTo(mutableSetOf()) { it.toString() }
        prefs.edit { putStringSet(key, currentIds) }
        // First run: the existing library is history, not news.
        if (known == null) return

        val added = current.filterKeys { it.toString() !in known }
        // Bulk imports (restore, Telegram) are not news either.
        if (added.isEmpty() || added.size > MAX_BURST) return

        // Our own activity is kept whether or not we share it: it is ours to look back on.
        val now = System.currentTimeMillis()
        merge(
            added.values.map { work ->
                SocialActivityItem(
                    eventId = "local:${kind.name}:${normalize(work.title)}:$now",
                    actorMxid = self,
                    kind = kind,
                    title = work.title,
                    coverUrl = work.cover?.takeIf { it.startsWith("http") },
                    timestamp = now,
                    source = work.source,
                )
            },
        )
        if (!_shareEnabled.value) return

        val roomId = roomMutex.withLock { prefs.getString(roomKey(self), null) } ?: return
        added.values.forEach { work ->
            val title = work.title
            val content = SocialActivityCodec.encode(kind, title, work.cover?.takeIf { it.startsWith("http") }, work.source)
            when (val sent = transport.sendMessage(roomId, content)) {
                is SocialResult.Success -> logcat(LogPriority.INFO) { "activity published: $title" }
                is SocialResult.Failure -> logcat(LogPriority.WARN) { "activity publish failed: ${sent.error}" }
            }
        }
    }

    private suspend fun ensureOwnRoom(self: String, friendIds: Set<String>) = roomMutex.withLock {
        val client = session.clientOrNull() ?: return@withLock
        var roomId = prefs.getString(roomKey(self), null)?.takeIf { isJoined(client, it) }
            ?: client.rooms().firstOrNull { room ->
                runCatching { room.topic() == SocialActivityCodec.topicFor(self) && room.membership() == Membership.JOINED }
                    .getOrDefault(false)
            }?.id()

        if (roomId == null) {
            val created = transport.createRoom(
                name = "Activité Shiro",
                topic = SocialActivityCodec.topicFor(self),
                initialStateJson = """{"kind":"activity"}""",
                invitees = friendIds.toList(),
            )
            roomId = (created as? SocialResult.Success)?.value ?: return@withLock
            logcat(LogPriority.INFO) { "activity room created" }
        }
        prefs.edit { putString(roomKey(self), roomId) }

        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return@withLock
        for (friend in friendIds) {
            val membership = runCatching { room.member(friend).membership }.getOrNull()
            if (membership is MembershipState.Join || membership is MembershipState.Invite) continue
            transport.invite(roomId, friend)
        }
    }

    private fun isJoined(client: Client, roomId: String): Boolean =
        runCatching { client.getRoom(roomId)?.membership() == Membership.JOINED }.getOrDefault(false)

    /** Last activity events of a room, read through a short-lived timeline. */
    private suspend fun readRecent(room: Room): List<SocialActivityItem> {
        val timeline = runCatching {
            room.timelineWithConfiguration(
                TimelineConfiguration(
                    focus = TimelineFocus.Live(hideThreadedEvents = false),
                    filter = TimelineFilter.All,
                    internalIdPrefix = "",
                    dateDividerMode = DateDividerMode.DAILY,
                    trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                    reportUtds = false,
                ),
            )
        }.getOrNull() ?: return emptyList()

        val items = Collections.synchronizedList(mutableListOf<TimelineItem>())
        val handle = timeline.addListener(
            object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diff.forEach { items += itemsOf(it) }
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
        return synchronized(items) { items.toList() }.mapNotNull(::toActivity).distinctBy { it.eventId }
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

    private fun toActivity(item: TimelineItem): SocialActivityItem? {
        val event = item.asEvent() ?: return null
        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return null
        val msgLike = event.content as? TimelineItemContent.MsgLike ?: return null
        val message = msgLike.content.kind as? MsgLikeKind.Message ?: return null
        val other = message.content.msgType as? MessageType.Other ?: return null
        return SocialActivityCodec.decode(other.msgtype, other.body, eventId, event.sender, event.timestamp.toLong())
    }

    private fun selfMxid(): String? = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId

    private fun roomKey(self: String) = "own_room_$self"

    private fun normalize(title: String) = title.lowercase().replace(Regex("[^a-z0-9]"), "")

    private companion object {
        const val KEY_SHARE = "share_activity"
        const val MAX_BURST = 3
        const val MAX_FEED = 300
        const val PAGE = 30
        const val PAGINATE_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 400L
    }
}
