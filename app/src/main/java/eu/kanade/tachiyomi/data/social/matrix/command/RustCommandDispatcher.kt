package eu.kanade.tachiyomi.data.social.matrix.command

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.MomentPayloadCodec
import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import eu.kanade.tachiyomi.data.social.matrix.ThreadRef
import eu.kanade.tachiyomi.data.social.matrix.room.RoomBindingStore
import kotlinx.coroutines.CancellationException
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
) : SocialCommandDispatcher {

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

            is SocialCommand.PublishToMyThread ->
                failure("PublishToMyThread requires self-thread binding (P2-10)")

            is SocialCommand.React -> withTimeline(client, command.thread) { timeline ->
                val ok = timeline.toggleReaction(
                    EventOrTransactionId.EventId(command.eventId),
                    command.key,
                )
                if (ok) SocialResult.Success("")
                else SocialResult.Failure(SocialError.Unknown("toggleReaction returned false"))
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

            is SocialCommand.Redact -> withTimeline(client, command.thread) { timeline ->
                timeline.redactEvent(
                    EventOrTransactionId.EventId(command.eventId),
                    command.reason,
                )
                SocialResult.Success("")
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
