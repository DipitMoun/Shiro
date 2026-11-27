package eu.kanade.tachiyomi.data.social.matrix.media

import android.util.LruCache
import eu.kanade.tachiyomi.data.social.matrix.ChatMedia
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Downloads the bytes of a chat image through the Matrix client (which also decrypts encrypted
 * attachments) and keeps the latest ones in memory so scrolling does not fetch them again.
 */
object SocialMediaLoader {

    private const val CACHE_BYTES = 32 * 1024 * 1024

    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    fun cached(media: ChatMedia): ByteArray? = cache.get(media.cacheKey)

    /** Same cache, for a source the SDK already handed over (stories): [key] identifies it. */
    suspend fun loadSource(key: String, source: MediaSource): ByteArray = withContext(Dispatchers.IO) {
        cache.get(key)?.let { return@withContext it }
        val client = Injekt.get<MatrixSessionManager>().clientOrNull() ?: error("Pas de session Matrix")
        client.getMediaContent(source).also { cache.put(key, it) }
    }

    suspend fun load(media: ChatMedia): ByteArray = withContext(Dispatchers.IO) {
        cache.get(media.cacheKey)?.let { return@withContext it }
        val client = Injekt.get<MatrixSessionManager>().clientOrNull()
            ?: error("Pas de session Matrix")
        val source = when {
            // The SDK expects {"file": <description>}. The bare description has a "url" key, so it
            // used to be read as a plain address: the encrypted bytes came back undecrypted.
            media.encryptedFileJson != null -> MediaSource.fromJson("""{"file":${media.encryptedFileJson}}""")
            media.url != null -> MediaSource.fromUrl(media.url)
            else -> error("Image sans adresse")
        }
        val bytes = client.getMediaContent(source)
        cache.put(media.cacheKey, bytes)
        bytes
    }
}
