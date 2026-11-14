package eu.kanade.tachiyomi.data.social.matrix.room

import android.content.Context
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.SocialRoomKey
import eu.kanade.tachiyomi.data.social.matrix.SocialThreadKey
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PersistentRoomBindingStore(context: Context) : RoomBindingStore {

    private val file = File(context.filesDir, "social/matrix/rooms.json").apply {
        parentFile?.mkdirs()
    }
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, String>? = null

    private fun load(): MutableMap<String, String> {
        if (cache != null) return cache!!
        return try {
            if (file.exists()) {
                val raw = file.readText()
                val map = json.decodeFromString<Map<String, String>>(raw).toMutableMap()
                cache = map
                map
            } else {
                val map = mutableMapOf<String, String>()
                cache = map
                map
            }
        } catch (t: Throwable) {
            val map = mutableMapOf<String, String>()
            cache = map
            map
        }
    }

    private fun save() {
        try {
            val raw = json.encodeToString(load())
            file.writeText(raw)
        } catch (t: Throwable) {
            // ignore
        }
    }

    override suspend fun resolveRoom(key: SocialRoomKey): SocialResult<String?> =
        SocialResult.Success(load()[key.value])

    override suspend fun bindRoom(key: SocialRoomKey, roomId: String): SocialResult<Unit> {
        load()[key.value] = roomId
        save()
        return SocialResult.Success(Unit)
    }

    override suspend fun migrateRoom(from: SocialRoomKey, to: SocialRoomKey): SocialResult<Unit> {
        val oldPrefix = from.value + SocialRoomKey.SEPARATOR
        val newPrefix = to.value + SocialRoomKey.SEPARATOR
        val map = load()
        map.keys.toList()
            .filter { it.startsWith(oldPrefix) }
            .forEach { key -> map[newPrefix + key.removePrefix(oldPrefix)] = map.getValue(key) }
        save()
        return SocialResult.Success(Unit)
    }

    override suspend fun roomsForAuthor(
        authorMxid: String,
    ): SocialResult<List<Pair<SocialRoomKey, String>>> = SocialResult.Success(
        load().entries.mapNotNull { (key, roomId) ->
            val parsed = SocialRoomKey.parse(key) ?: return@mapNotNull null
            if (parsed.authorMxid == authorMxid) parsed to roomId else null
        },
    )

    override suspend fun resolveThreadRoot(key: SocialThreadKey): SocialResult<String?> =
        SocialResult.Success(load()[key.value])

    override suspend fun bindThreadRoot(key: SocialThreadKey, rootEventId: String): SocialResult<Unit> {
        load()[key.value] = rootEventId
        save()
        return SocialResult.Success(Unit)
    }
}
