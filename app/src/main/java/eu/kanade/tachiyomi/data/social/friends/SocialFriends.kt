package eu.kanade.tachiyomi.data.social.friends

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.social.audience.SocialFriend
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SocialFriendRecord(
    val mxid: String,
    val displayName: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

interface SocialFriendStore {
    val friends: StateFlow<List<SocialFriendRecord>>

    suspend fun add(record: SocialFriendRecord)
    suspend fun remove(mxid: String)
    fun isFriend(mxid: String): Boolean
    suspend fun rejectFriendRequest(mxid: String) {}
    fun isRejected(mxid: String): Boolean = false
    fun asAudienceFriends(): List<SocialFriend> = friends.value.map {
        SocialFriend(userId = it.mxid, displayName = it.displayName, hasWork = true)
    }
}

class PersistentSocialFriendStore(context: Context) : SocialFriendStore {

    private val file = File(context.filesDir, "social/friends.json").apply {
        parentFile?.mkdirs()
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val _friends = MutableStateFlow(load())
    override val friends: StateFlow<List<SocialFriendRecord>> = _friends.asStateFlow()

    private val rejectedFile = File(context.filesDir, "social/rejected_mxids.json").apply {
        parentFile?.mkdirs()
    }
    private val _rejectedMxids = MutableStateFlow(loadRejected())

    override suspend fun add(record: SocialFriendRecord) {
        if (!SocialProfileLink.isValidMxid(record.mxid)) return
        val next = _friends.value
            .filterNot { it.mxid == record.mxid }
            .plus(record.copy(mxid = record.mxid.trim()))
            .sortedBy { it.displayName ?: it.mxid }
        _friends.value = next
        save()
    }

    override suspend fun remove(mxid: String) {
        val next = _friends.value.filterNot { it.mxid == mxid }
        if (next.size == _friends.value.size) return
        _friends.value = next
        save()
    }

    override fun isFriend(mxid: String): Boolean = _friends.value.any { it.mxid == mxid }

    override suspend fun rejectFriendRequest(mxid: String) {
        if (!SocialProfileLink.isValidMxid(mxid)) return
        _rejectedMxids.value = _rejectedMxids.value + mxid
        saveRejected()
    }

    override fun isRejected(mxid: String): Boolean = _rejectedMxids.value.contains(mxid)

    private fun load(): List<SocialFriendRecord> = try {
        if (file.exists()) {
            json.decodeFromString<List<SocialFriendRecord>>(file.readText())
        } else {
            emptyList()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun save() {
        try {
            file.writeText(json.encodeToString(_friends.value))
        } catch (_: Throwable) {
            // Keep the in-memory list usable even if the disk write fails.
        }
    }

    private fun loadRejected(): Set<String> = try {
        if (rejectedFile.exists()) {
            json.decodeFromString<Set<String>>(rejectedFile.readText())
        } else {
            emptySet()
        }
    } catch (_: Throwable) {
        emptySet()
    }

    private fun saveRejected() {
        try {
            rejectedFile.writeText(json.encodeToString(_rejectedMxids.value))
        } catch (_: Throwable) {
            // Keep the in-memory set usable even if the disk write fails.
        }
    }
}

/**
 * Local profile link carrying only the Matrix id. It intentionally contains no
 * room id and no work metadata.
 */
object SocialProfileLink {
    const val SCHEME = "shiro"
    const val HOST = "social"
    const val PATH = "/friend"

    private val MXID = Regex("^@[^:\\s]+:[^:\\s]+$")

    fun isValidMxid(raw: String): Boolean = MXID.matches(raw.trim())

    fun encode(mxid: String, displayName: String? = null): String {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .path(PATH)
            .appendQueryParameter("mxid", mxid.trim())
        displayName?.takeIf { it.isNotBlank() }?.let {
            builder.appendQueryParameter("name", it)
        }
        return builder.build().toString()
    }

    fun decode(raw: String): SocialFriendRecord? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.authority != HOST || uri.path != PATH) return null
        val mxid = uri.getQueryParameter("mxid")?.trim() ?: return null
        if (!isValidMxid(mxid)) return null
        return SocialFriendRecord(
            mxid = mxid,
            displayName = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() },
        )
    }
}
