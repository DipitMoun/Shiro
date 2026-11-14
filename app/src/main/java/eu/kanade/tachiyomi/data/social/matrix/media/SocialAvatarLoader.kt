package eu.kanade.tachiyomi.data.social.matrix.media

import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource

/** Loads Matrix `mxc://` avatars without exposing the SDK to the UI. */
interface SocialAvatarLoader {
    suspend fun loadAvatar(mxcUrl: String, sizePx: Int = 96): ByteArray?

    suspend fun loadOwnAvatar(sizePx: Int = 96): ByteArray?

    suspend fun loadProfileAvatar(mxid: String, sizePx: Int = 96): ByteArray?
}

class RustSocialAvatarLoader(
    private val clientProvider: () -> Client?,
) : SocialAvatarLoader {

    override suspend fun loadAvatar(mxcUrl: String, sizePx: Int): ByteArray? {
        val client = clientProvider() ?: return null
        return try {
            val source = MediaSource.fromUrl(mxcUrl)
            client.getMediaThumbnail(source, sizePx.toULong(), sizePx.toULong())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            android.util.Log.w("SocialAvatar", "avatar load failed: ${t.message}")
            null
        }
    }

    override suspend fun loadOwnAvatar(sizePx: Int): ByteArray? {
        val client = clientProvider() ?: return null
        val url = try {
            client.avatarUrl()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            android.util.Log.w("SocialAvatar", "own avatar URL failed: ${t.message}")
            null
        } ?: return null
        return loadAvatar(url, sizePx)
    }

    override suspend fun loadProfileAvatar(mxid: String, sizePx: Int): ByteArray? {
        val client = clientProvider() ?: return null
        val url = try {
            client.getProfile(mxid).avatarUrl
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            android.util.Log.w("SocialAvatar", "profile avatar failed for $mxid: ${t.message}")
            null
        } ?: return null
        return loadAvatar(url, sizePx)
    }
}
