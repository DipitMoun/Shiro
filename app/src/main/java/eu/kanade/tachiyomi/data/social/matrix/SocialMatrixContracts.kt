package eu.kanade.tachiyomi.data.social.matrix

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Internal contracts of the Matrix social layer (task P2-02).
 *
 * These types define the boundaries between the UI, the domain repositories and the
 * Matrix SDK/store. They are deliberately SDK-independent: no Android types, no Matrix
 * SDK types, no HTTP client types. Implementations (Rust SDK adapter, fake transport for
 * tests, local store) plug into these interfaces without leaking their own model upward.
 *
 * Conventions:
 * - Matrix identifiers (mxid, room id, event id, sync token) are opaque strings; they are
 *   validated at the transport boundary, not in the domain.
 * - Every fallible operation returns [SocialResult]; implementations never throw for
 *   expected failures (network, auth, encryption, permission, rate limit).
 * - Canonical identity ([WorkId], [EpisodeKey]) and the wire payload ([MomentPayload])
 *   are reused as-is; this file adds no parallel model.
 */

// ---------------------------------------------------------------------------
// Results and errors
// ---------------------------------------------------------------------------

/** Outcome of every fallible social operation. */
sealed interface SocialResult<out T> {
    data class Success<T>(val value: T) : SocialResult<T>
    data class Failure(val error: SocialError) : SocialResult<Nothing>
}

/**
 * Classified failure shared by the transport, the repositories and the commands.
 *
 * The classification drives UI copy and retry policy, so implementations must map SDK
 * or HTTP errors into these categories instead of forwarding raw exceptions.
 */
sealed interface SocialError {
    /** Unreachable network, DNS failure, timeout, homeserver 5xx. */
    data class Network(
        /** True when retrying the same call can succeed without user action. */
        val transient: Boolean,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : SocialError

    /** Login, access token or account level failure. */
    data class Auth(val reason: AuthFailureReason, val message: String? = null) : SocialError

    /** E2EE failure: content cannot be decrypted or the room is not safely encrypted. */
    data class Encryption(val reason: EncryptionFailureReason, val message: String? = null) : SocialError

    /** Homeserver or room power level denied the action (HTTP 403). */
    data class Permission(val message: String? = null) : SocialError

    /** HTTP 429; [retryAfterMs] is taken from the server response when provided. */
    data class RateLimit(val retryAfterMs: Long? = null) : SocialError

    /** Anything that does not fit the categories above; must not be used for expected failures. */
    data class Unknown(val message: String? = null, val cause: Throwable? = null) : SocialError
}

enum class AuthFailureReason {
    INVALID_CREDENTIALS,
    TOKEN_EXPIRED,
    NOT_ALLOWED,
    UNKNOWN,
}

enum class EncryptionFailureReason {
    MISSING_KEYS,
    UNVERIFIED_DEVICE,
    KEY_BACKUP_INCOMPLETE,
    UNKNOWN,
}

// ---------------------------------------------------------------------------
// Session and account state
// ---------------------------------------------------------------------------

/** Authenticated Matrix account bound to the social layer. */
data class SocialAccount(
    /** User mxid, e.g. `@alice:matrix.org`. */
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val displayName: String? = null,
)

/** Lifecycle of the Matrix session backing the social layer. */
sealed interface SocialSessionState {
    /** No stored credentials; social surfaces must stay hidden. */
    data object Unauthenticated : SocialSessionState

    /** Login, token refresh or session restore in progress. */
    data class Authenticating(
        /** Target homeserver; null during a restore before the store is read. */
        val homeserverUrl: String? = null,
    ) : SocialSessionState

    data class Authenticated(val account: SocialAccount) : SocialSessionState

    /** Terminal failure until the user retries or logs out. */
    data class Error(val error: SocialError) : SocialSessionState
}

/**
 * Observable session holder.
 *
 * The login flow (task P2-05) drives [state]; repositories and the command dispatcher
 * must refuse work with [SocialError.Auth] while the state is not
 * [SocialSessionState.Authenticated].
 */
interface SocialSession {
    val state: StateFlow<SocialSessionState>
}

// ---------------------------------------------------------------------------
// SDK-independent Matrix transport
// ---------------------------------------------------------------------------

/**
 * Event projection crossing the transport boundary.
 *
 * [contentJson] is the decrypted `content` object of the event as a JSON string, so the
 * boundary never depends on an SDK JSON type. Decoding into [MomentPayload] stays on the
 * domain side via [MomentPayloadCodec].
 */
data class TransportEvent(
    val eventId: String,
    val roomId: String,
    /** Sender mxid. */
    val sender: String,
    /** Matrix event type, e.g. `m.room.message`, `m.reaction`, `org.shiro.pins`. */
    val type: String,
    /** Non-null only for state events. */
    val stateKey: String? = null,
    /** `origin_server_ts`, milliseconds since epoch. */
    val originServerTs: Long,
    /** Decrypted event content; empty object for a redacted event. */
    val contentJson: String,
    /** Event id of the redaction when this event has been redacted. */
    val redactedByEventId: String? = null,
)

data class SyncRequest(
    /** Opaque token from the previous [SyncUpdate]; null performs an initial sync. */
    val since: String? = null,
    /** Long-poll timeout; 0 returns as soon as the server has data. */
    val timeoutMs: Long = 0,
)

data class SyncUpdate(
    /** Token to pass as [SyncRequest.since] on the next call. */
    val nextBatch: String,
    /** Timeline, state and relation events received since the previous batch. */
    val events: List<TransportEvent>,
)

data class SendRequest(
    val roomId: String,
    /** Client-generated idempotency key so a retry never duplicates the event. */
    val transactionId: String,
    /** Matrix event type; defaults to `m.room.message`. */
    val type: String = "m.room.message",
    val contentJson: String,
)

/**
 * Thin boundary over the Matrix client: the Rust SDK adapter in production, a fake in tests.
 *
 * Contract:
 * - every call is suspend and returns [SocialResult]; expected failures never throw;
 * - `join` resolves to the joined room id; `send`, `redact` and `react` resolve to the id
 *   of the event they created;
 * - implementations translate SDK/HTTP errors into [SocialError] categories;
 * - room creation, state events and invites are intentionally not part of this interface:
 *   they belong to the rooms/threads task (P2-06) and will extend it as separate contracts.
 */
interface MatrixTransport {
    /** One incremental `/sync` step. */
    suspend fun sync(request: SyncRequest): SocialResult<SyncUpdate>

    /** Accepts a room invitation. */
    suspend fun join(roomIdOrAlias: String): SocialResult<String>

    /** Sends one event; idempotent per [SendRequest.transactionId]. */
    suspend fun send(request: SendRequest): SocialResult<String>

    /** Redacts an event; resolves to the redaction event id. */
    suspend fun redact(roomId: String, eventId: String, reason: String?): SocialResult<String>

    /** Sends an `m.reaction` annotating [eventId] with [key]. */
    suspend fun react(roomId: String, eventId: String, key: String): SocialResult<String>
}

// ---------------------------------------------------------------------------
// Thread timeline (read side)
// ---------------------------------------------------------------------------

/**
 * Identity of one thread: an author's room for a work, scoped to one episode.
 *
 * The author can be a friend: replying in their thread stays in their room and their
 * first-degree audience, never mirrored across rooms.
 */
data class ThreadRef(
    /** Room owner mxid; not necessarily the current user. */
    val authorMxid: String,
    val workId: WorkId,
    val episodeKey: EpisodeKey,
)

/** One `m.reaction`, with its event id so an own reaction can be removed by redaction. */
data class ReactionEntry(
    /** Reactor mxid. */
    val sender: String,
    /** Event id of the reaction itself. */
    val eventId: String,
)

/** Reactions on one event, scoped to a single room; no cross-room aggregation in v1. */
data class ReactionSummary(
    val key: String,
    val reactions: List<ReactionEntry>,
) {
    val count: Int get() = reactions.size
}

/** Local echo state of an outgoing event, until the server confirms or rejects it. */
enum class DeliveryState {
    PENDING,
    SENT,
    FAILED,
}

/** One rendered entry of a thread; redacted events are removed, never rendered. */
sealed interface SocialTimelineItem {
    val eventId: String
    /** Sender mxid. */
    val sender: String
    val sentAt: Instant
    val reactions: List<ReactionSummary>
    val deliveryState: DeliveryState

    /** `m.room.message` carrying a decoded `org.shiro.moment` payload. */
    data class MomentItem(
        override val eventId: String,
        override val sender: String,
        override val sentAt: Instant,
        /** Newest valid content; reflects `m.replace` edits. */
        val payload: MomentPayload,
        /** True when the event was replaced at least once. */
        val edited: Boolean,
        /** True when pinned as a "key moment" by the latest valid `org.shiro.pins`. */
        val pinned: Boolean,
        override val reactions: List<ReactionSummary>,
        override val deliveryState: DeliveryState,
    ) : SocialTimelineItem

    /** Plain threaded reply; never carries a moment payload. */
    data class ReplyItem(
        override val eventId: String,
        override val sender: String,
        override val sentAt: Instant,
        val body: String,
        /** `m.in_reply_to` target inside the thread, when any. */
        val inReplyToEventId: String?,
        override val reactions: List<ReactionSummary>,
        override val deliveryState: DeliveryState,
    ) : SocialTimelineItem
}

/** Liveness of the timeline feed for one thread. */
enum class TimelineStatus {
    /** Not synced yet; nothing is known locally. */
    IDLE,
    /** Initial sync or catch-up in progress. */
    SYNCING,
    /** Receiving live updates. */
    LIVE,
    /** Transport down; cached items are still displayed. */
    OFFLINE,
}

data class SocialThreadTimeline(
    val thread: ThreadRef,
    /** Null until the (author, work) room is resolved locally or joined. */
    val roomId: String?,
    /** Event id of the episode thread root; null until it is created lazily. */
    val threadRootEventId: String?,
    /** Chronological, oldest first. */
    val items: List<SocialTimelineItem>,
    val status: TimelineStatus,
    /** Opaque backward pagination cursor; null when the thread start is reached. */
    val olderCursor: String? = null,
)

data class TimelinePage(
    /** Older items, chronological, oldest first. */
    val items: List<SocialTimelineItem>,
    /** Cursor for the next older page; null at the thread start. */
    val nextCursor: String? = null,
)

/**
 * Read side of the social layer: projects synced Matrix events into a single
 * author + work + episode thread.
 *
 * The implementation owns the local bindings (`room_binding`, `thread_binding`,
 * `moment_index`) and must not expose Matrix SDK types. Pins, edits, reactions and
 * redactions are already aggregated inside the emitted items.
 */
interface SocialThreadRepository {
    /**
     * Continuous observation of one thread: emits cached data immediately, then live
     * updates. [SocialResult.Failure] is emitted only when the thread cannot be projected
     * at all (e.g. auth lost); transport hiccups surface as [TimelineStatus.OFFLINE].
     */
    fun observeThread(thread: ThreadRef): Flow<SocialResult<SocialThreadTimeline>>

    /** Fetches one page of older items from a [SocialThreadTimeline.olderCursor]. */
    suspend fun pageBack(thread: ThreadRef, cursor: String): SocialResult<TimelinePage>
}

// ---------------------------------------------------------------------------
// Commands (write side)
// ---------------------------------------------------------------------------

/**
 * Write side of the social layer, modeled as data commands so a single dispatcher can
 * queue, rate-limit and retry them uniformly (P2-12 hardening).
 */
sealed interface SocialCommand {
    /**
     * "Reply here": plain reply inside the author's thread. Stays in the author's room
     * and audience; never creates a moment.
     */
    data class ReplyInAuthorThread(
        val thread: ThreadRef,
        val body: String,
        /** Moment being answered, used for `m.in_reply_to`. */
        val inReplyToEventId: String? = null,
    ) : SocialCommand

    /**
     * "Publish to my friends": moment published in the current user's own thread for the
     * work + episode carried by the payload. The fallback `body` is derived via
     * [MomentPayloadCodec.fallbackBody]; the room and the thread root are created lazily
     * when they do not exist yet.
     */
    data class PublishToMyThread(
        val payload: MomentPayload,
    ) : SocialCommand

    /** `m.reaction` on a whole moment event; [key] is typically from the quick set. */
    data class React(
        val thread: ThreadRef,
        val eventId: String,
        val key: String,
    ) : SocialCommand

    /**
     * `m.replace` edit of one of our events. [payload] is required when the target is a
     * moment: the new `org.shiro.moment` goes into `m.new_content` next to [body]. Null
     * for a plain reply.
     */
    data class Edit(
        val thread: ThreadRef,
        val eventId: String,
        val body: String,
        val payload: MomentPayload? = null,
    ) : SocialCommand

    /**
     * Redaction of one of our events; the moment or reaction disappears from the timeline
     * once confirmed.
     */
    data class Redact(
        val thread: ThreadRef,
        val eventId: String,
        /** Server-visible reason; must not contain spoilers. */
        val reason: String? = null,
    ) : SocialCommand
}

/**
 * Executes [SocialCommand]s; resolves to the id of the event the command created
 * (message, reaction, replacement or redaction event).
 *
 * The dispatcher is the single write choke point: it checks that the session is
 * [SocialSessionState.Authenticated], applies rate-limit backoff from
 * [SocialError.RateLimit.retryAfterMs] and updates the local echo state.
 */
interface SocialCommandDispatcher {
    suspend fun execute(command: SocialCommand): SocialResult<String>
}

// ---------------------------------------------------------------------------
// Feature flags
// ---------------------------------------------------------------------------

/** Independently disableable social surfaces. */
enum class SocialFeature {
    /** Whole moment overlay on the player. */
    MOMENT_OVERLAY,
    PUBLISH,
    REACTIONS,
    PINS,
    INSTANT_NOTIFICATIONS,
}

/**
 * Social layer gating (P2-10). When a feature is off, the UI must hide its entry points
 * entirely rather than show broken or disabled social surfaces.
 */
interface SocialFeatureFlags {
    fun isEnabled(feature: SocialFeature): Boolean

    /** Live observation of one flag, e.g. for rollout or kill-switch changes. */
    fun observe(feature: SocialFeature): Flow<Boolean>
}
