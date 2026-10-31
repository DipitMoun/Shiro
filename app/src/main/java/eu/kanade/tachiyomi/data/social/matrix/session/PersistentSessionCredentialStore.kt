package eu.kanade.tachiyomi.data.social.matrix.session

import android.content.Context
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Simple file-based [SessionCredentialStore] for development.
 * Saves the persisted session to a JSON file.
 *
 * TODO(P2-11): replace with EncryptedSharedPreferences before release.
 */
class PersistentSessionCredentialStore(context: Context) : SessionCredentialStore {

    private val file = File(context.filesDir, "social/matrix/session.json").apply {
        parentFile?.mkdirs()
    }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): SocialResult<PersistedSession?> = try {
        if (!file.exists()) {
            SocialResult.Success(null)
        } else {
            val raw = file.readText()
            val session = json.decodeFromString<PersistedSession>(raw)
            SocialResult.Success(session)
        }
    } catch (t: Throwable) {
        SocialResult.Failure(SocialError.Unknown("Failed to load session: ${t.message}", t))
    }

    override suspend fun save(session: PersistedSession): SocialResult<Unit> = try {
        val raw = json.encodeToString(PersistedSession.serializer(), session)
        file.writeText(raw)
        SocialResult.Success(Unit)
    } catch (t: Throwable) {
        SocialResult.Failure(SocialError.Unknown("Failed to save session: ${t.message}", t))
    }

    override suspend fun clear(): SocialResult<Unit> = try {
        if (file.exists()) {
            file.delete()
        }
        SocialResult.Success(Unit)
    } catch (t: Throwable) {
        SocialResult.Failure(SocialError.Unknown("Failed to clear session: ${t.message}", t))
    }
}
