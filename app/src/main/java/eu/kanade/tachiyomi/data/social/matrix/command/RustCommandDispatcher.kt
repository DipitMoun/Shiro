package eu.kanade.tachiyomi.data.social.matrix.command

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MomentPayloadCodec
import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.room.MatrixRoomTransport
import eu.kanade.tachiyomi.data.social.matrix.room.RoomBindingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/**
 * P2-08: production [SocialCommandDispatcher] wired to the Rust Matrix SDK.
 *
 * Signatures below are derived from actual compiler errors on sdk-android 26.10.23;
 * see PHASE-2-PROGRESS.md for the full list of verified signatures.
 *
 * Return-value policy:
 * - Send-like commands (sendReply, sendWithExtraContent) resolve to a SendHandle
 *   on the SDK. The server eventId is confirmed asynchronously (P2-11);
 *   this dispatcher returns Success("") until then.
 * - edit / redactEvent return Unit; Success("") on ok.
 * - toggleReaction returns Boolean; false maps to Failure(Unknown).
 */
class RustCommandDispatcher(
    private val clientProvider: () -> Client?,
    private val bindings: RoomBindingStore,
    private val roomTransport: MatrixRoomTransport,
) : SocialCommandDispatcher {

    /**
     * Sends again when the network dropped. After the app wakes up from a notification the HTTP
     * connection kept by the SDK is often already dead: the first request is reset, the next one
     * goes through.
     */
    private suspend fun retryingOnNetworkError(send: suspend () -> SocialResult<String>): SocialResult<String> {
        var attempt = 0
        while (true) {
            val result = send()
            val error = (result as? SocialResult.Failure)?.error
            val retryable = error is SocialError.Network && error.transient
            if (!retryable || ++attempt >= SEND_ATTEMPTS) return result
            delay(SEND_RETRY_DELAY_MS * attempt)
        }
    }

    override suspend fun execute(command: SocialCommand): SocialResult<String> {
        val client = clientProvider() ?: return notAuthenticated()

        return when (command) {
            is SocialCommand.ReplyInAuthorThread -> withTimeline(client, command.thread) { timeline ->
                val content = timeline.makeTextContent(command.body)
                    ?: return@withTimeline failure("createMessageContent returned null")
                val target = command.inReplyToEventId
                    ?: resolveRoot(command.thread)
                    ?: return@withTimeline failure("Thread root not bound")
                timeline.sendReply(content, target)
                SocialResult.Success("")
            }

            is SocialCommand.SendToRoom ->
                retryingOnNetworkError {
                    roomTransport.sendMessage(
                        roomId = command.roomId,
                        contentJson = textContent(command.body, command.inReplyToEventId),
                    )
                }

            is SocialCommand.PublishToMyThread -> {
                val payload = command.payload
                val validation = MomentPayloadCodec.validate(payload)
                if (validation.isNotEmpty()) {
                    return failure("Invalid moment payload: ${validation.joinToString("; ")}")
                }
                val workId = WorkId.parse(payload.work.id)
                    ?: return failure("Invalid work id: ${payload.work.id}")
                val episodeKey = EpisodeKey.parse(payload.episode.key)
                    ?: return failure("Invalid episode key: ${payload.episode.key}")
                val selfMxid = try {
                    client.userId()
                } catch (t: Throwable) {
                    return failure("Cannot resolve current user: ${t.message}")
                }
                val thread = ThreadRef(selfMxid, workId, episodeKey)
                withTimeline(client, thread) { timeline ->
                    val content = timeline.makeTextContent(MomentPayloadCodec.fallbackBody(payload))
                        ?: return@withTimeline failure("createMessageContent returned null")
                    val extra = """{"org.shiro.moment":${MomentPayloadCodec.encode(payload)}}"""
                    timeline.sendWithExtraContent(content, extra)
                    SocialResult.Success("")
                }
            }

            is SocialCommand.PublishToRoom -> {
                val payload = command.payload
                val validation = MomentPayloadCodec.validate(payload)
                if (validation.isNotEmpty()) {
                    return failure("Invalid moment payload: ${validation.joinToString("; ")}")
                }
                val content = buildJsonObject {
                    put("msgtype", "m.text")
                    put("body", MomentPayloadCodec.fallbackBody(payload))
                    put("org.shiro.moment", json.parseToJsonElement(MomentPayloadCodec.encode(payload)))
                }
                retryingOnNetworkError { roomTransport.sendMessage(command.roomId, content.toString()) }
            }

            is SocialCommand.React -> {
                val roomId = resolveRoom(command.thread)
                    ?: return failure("Room not bound for ${command.thread.describe()}")
                val room = client.getRoom(roomId)
                    ?: return failure("Room $roomId not found")
                val rootId = resolveRoot(command.thread)
                    ?: return failure("Thread root not bound for ${command.thread.describe()}")
                val timeline = openTimeline(room, rootId)
                try {
                    // Load the current thread window so the target item is
                    // present, including its aggregated reactions. Without
                    // this, toggleReaction cannot detect an existing reaction
                    // and Matrix rejects the duplicate instead of removing it.
                    timeline.paginateBackwards(100.toUShort())
                    // true = added, false = removed; both are successful
                    // outcomes of a toggle.
                    timeline.toggleReaction(
                        EventOrTransactionId.EventId(command.eventId),
                        command.key,
                    )
                    SocialResult.Success("")
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // Matrix rejects adding the same reaction twice. The UI is
                    // already showing that reaction, so treat it as a no-op.
                    if (t.message?.contains("same reaction", ignoreCase = true) == true) {
                        SocialResult.Success("")
                    } else {
                        SocialResult.Failure(mapError(t, "react"))
                    }
                } finally {
                    try { timeline.close() } catch (_: Throwable) {}
                }
            }

            is SocialCommand.ReactInRoom -> {
                val room = client.getRoom(command.roomId)
                    ?: return failure("Room ${command.roomId} not found")
                val timeline = openLiveTimeline(room)
                try {
                    timeline.paginateBackwards(100.toUShort())
                    timeline.toggleReaction(
                        EventOrTransactionId.EventId(command.eventId),
                        command.key,
                    )
                    SocialResult.Success("")
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (t.message?.contains("same reaction", ignoreCase = true) == true) {
                        SocialResult.Success("")
                    } else {
                        SocialResult.Failure(mapError(t, "react"))
                    }
                } finally {
                    try { timeline.close() } catch (_: Throwable) {}
                }
            }

            is SocialCommand.Edit -> withTimeline(client, command.thread) { timeline ->
                val content = timeline.makeTextContent(command.body)
                    ?: return@withTimeline failure("createMessageContent returned null")
                timeline.edit(
                    EventOrTransactionId.EventId(command.eventId),
                    EditedContent.RoomMessage(content),
                )
                SocialResult.Success("")
            }

            is SocialCommand.EditInRoom -> {
                val room = client.getRoom(command.roomId)
                    ?: return failure("Room ${command.roomId} not found")
                val timeline = openLiveTimeline(room)
                try {
                    val content = timeline.makeTextContent(command.body)
                        ?: return failure("createMessageContent returned null")
                    timeline.edit(
                        EventOrTransactionId.EventId(command.eventId),
                        EditedContent.RoomMessage(content),
                    )
                    SocialResult.Success("")
                } finally {
                    try { timeline.close() } catch (_: Throwable) {}
                }
            }

            is SocialCommand.Redact -> withTimeline(client, command.thread) { timeline ->
                timeline.redactEvent(
                    EventOrTransactionId.EventId(command.eventId),
                    command.reason,
                )
                SocialResult.Success("")
            }

            is SocialCommand.RedactInRoom -> {
                val room = client.getRoom(command.roomId)
                    ?: return failure("Room ${command.roomId} not found")
                val timeline = openLiveTimeline(room)
                try {
                    timeline.redactEvent(
                        EventOrTransactionId.EventId(command.eventId),
                        command.reason,
                    )
                    SocialResult.Success("")
                } finally {
                    try { timeline.close() } catch (_: Throwable) {}
                }
            }
        }
    }

    private suspend fun withTimeline(
        client: Client,
        thread: ThreadRef,
        block: suspend (Timeline) -> SocialResult<String>,
    ): SocialResult<String> {
        return try {
            val roomId = resolveRoom(thread)
                ?: return failure("Room not bound for ${thread.describe()}")
            val rootId = resolveRoot(thread)
                ?: return failure("Thread root not bound for ${thread.describe()}")
            val room = client.getRoom(roomId)
                ?: return failure("Room $roomId not found")
            val timeline = openTimeline(room, rootId)
            try { block(timeline) }
            finally { try { timeline.close() } catch (_: Throwable) {} }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "execute"))
        }
    }

    private suspend fun openTimeline(room: Room, rootId: String): Timeline {
        val config = TimelineConfiguration(
            focus = TimelineFocus.Thread(rootId),
            filter = TimelineFilter.All,
            internalIdPrefix = "",
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
            reportUtds = false,
        )
        return room.timelineWithConfiguration(config)
    }

    private suspend fun openLiveTimeline(room: Room): Timeline {
        val config = TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false),
            filter = TimelineFilter.All,
            internalIdPrefix = "",
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
            reportUtds = false,
        )
        return room.timelineWithConfiguration(config)
    }

    private fun textContent(body: String, inReplyToEventId: String?): String =
        buildJsonObject {
            put("msgtype", "m.text")
            put("body", body)
            if (inReplyToEventId != null) {
                putJsonObject("m.relates_to") {
                    putJsonObject("m.in_reply_to") {
                        put("event_id", inReplyToEventId)
                    }
                }
            }
        }.toString()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Builds a text content. Returns null on any SDK exception. */
    private fun Timeline.makeTextContent(body: String) =
        try { createMessageContent(MessageType.Text(TextMessageContent(body, null))) }
        catch (_: Throwable) { null }

    private suspend fun resolveRoom(thread: ThreadRef): String? {
        val key = SocialRoomKey(authorMxid = thread.authorMxid, workId = thread.workId)
        return (bindings.resolveRoom(key) as? SocialResult.Success)?.value
    }

    private suspend fun resolveRoot(thread: ThreadRef): String? {
        val roomKey = SocialRoomKey(authorMxid = thread.authorMxid, workId = thread.workId)
        val threadKey = SocialThreadKey(room = roomKey, episodeKey = thread.episodeKey)
        return (bindings.resolveThreadRoot(threadKey) as? SocialResult.Success)?.value
    }

    private fun notAuthenticated(): SocialResult.Failure = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )

    private fun failure(msg: String): SocialResult.Failure =
        SocialResult.Failure(SocialError.Unknown(msg))

    private fun mapError(t: Throwable, op: String): SocialError {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            "unauthor" in lower || "forbidden" in lower -> SocialError.Permission(msg)
            "network" in lower || "timed out" in lower || "connect" in lower ->
                SocialError.Network(transient = true, message = msg, cause = t)
            "rate" in lower || "too many" in lower -> SocialError.RateLimit(retryAfterMs = null)
            else -> SocialError.Unknown("$op failed: $msg", t)
        }
    }

    private fun ThreadRef.describe(): String =
        "${authorMxid}/${workId.value}/${episodeKey.value}"
}

// A message is tried up to three times when the connection drops.
private const val SEND_ATTEMPTS = 3
private const val SEND_RETRY_DELAY_MS = 800L
