package eu.kanade.tachiyomi.data.social

import eu.kanade.tachiyomi.data.social.activity.SocialActivityCodec
import eu.kanade.tachiyomi.data.social.activity.SocialActivityKind
import eu.kanade.tachiyomi.data.social.audience.SocialFriend
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.data.social.identity.WorkIdentity
import eu.kanade.tachiyomi.data.social.matrix.room.DefaultSocialRoomService
import eu.kanade.tachiyomi.data.social.matrix.room.FakeMatrixRoomTransport
import eu.kanade.tachiyomi.data.social.matrix.room.InMemoryRoomBindingStore
import eu.kanade.tachiyomi.data.social.matrix.room.RoomSourceMemo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkSourceRefTest {

    private val ref = WorkSourceRef(
        medium = WorkSourceRef.ANIME,
        sourceId = 123456789L,
        sourceName = "Anime-Sama",
        pkg = "eu.kanade.tachiyomi.animeextension.fr.animesama",
        url = "/catalogue/jojo/saison1/vostfr",
        title = "JoJo",
    )

    @Test
    fun `a reference survives encoding`() {
        assertEquals(ref, WorkSourceRef.decode(ref.encode()))
    }

    @Test
    fun `an incomplete or unreadable reference is ignored`() {
        assertNull(WorkSourceRef.decode(null))
        assertNull(WorkSourceRef.decode("not json"))
        assertNull(WorkSourceRef.decode(ref.copy(pkg = "").encode()))
        assertNull(WorkSourceRef.decode(ref.copy(url = " ").encode()))
    }

    @Test
    fun `an activity keeps the source of the work`() {
        val content = SocialActivityCodec.encode(SocialActivityKind.ANIME_ADDED, "JoJo", "https://x/c.jpg", ref)
        val obj = Json.parseToJsonElement(content).jsonObject
        val item = SocialActivityCodec.decode(
            msgtype = obj["msgtype"]!!.toString().trim('"'),
            body = obj["body"]!!.toString().let { Json.decodeFromString<String>(it) },
            eventId = "\$e",
            senderMxid = "@a:b.c",
            timestamp = 1L,
        )

        assertEquals(ref, item!!.source)
    }

    @Test
    fun `an activity from an older app still reads, without a source`() {
        val item = SocialActivityCodec.decode(
            msgtype = SocialActivityKind.MANGA_ADDED.msgtype,
            body = """{"t":"Berserk","c":"https://x/c.jpg"}""",
            eventId = "\$e",
            senderMxid = "@a:b.c",
            timestamp = 1L,
        )

        assertNotNull(item)
        assertNull(item!!.source)
    }

    private val alice = "@alice:example.org"
    private val work = WorkIdentity(
        id = WorkId.parse("local:abc123")!!,
        ids = mapOf("local" to "abc123"),
        title = "JoJo",
        source = ref,
    )

    private fun serviceWith(transport: FakeMatrixRoomTransport, memo: RoomSourceMemo) =
        DefaultSocialRoomService(transport, InMemoryRoomBindingStore(), memo)

    @Test
    fun `a new room records the source in its state`() = runBlocking {
        val transport = FakeMatrixRoomTransport()
        serviceWith(transport, RoomSourceMemo.Never).ensureRoom(alice, "Alice", alice, work, emptyList())

        assertTrue(transport.lastCreateRoomState.orEmpty().contains("\"pkg\":\"${ref.pkg}\""))
    }

    @Test
    fun `an existing room gets the source once`() = runBlocking {
        val transport = FakeMatrixRoomTransport()
        val seen = mutableSetOf<String>()
        val memo = object : RoomSourceMemo {
            override fun firstTime(roomId: String, stamp: String) = seen.add("$roomId|$stamp")
        }
        val service = serviceWith(transport, memo)
        service.ensureRoom(alice, "Alice", alice, work, emptyList()) // creates
        val before = transport.sendStateEventCalls

        service.ensureRoom(alice, "Alice", alice, work, emptyList()) // existing: first time
        service.ensureRoom(alice, "Alice", alice, work, emptyList()) // existing: already sent

        assertEquals(before + 1, transport.sendStateEventCalls)
    }
}
