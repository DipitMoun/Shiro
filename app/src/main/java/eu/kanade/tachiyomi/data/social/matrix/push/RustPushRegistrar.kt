package eu.kanade.tachiyomi.data.social.matrix.push

import eu.kanade.tachiyomi.data.social.matrix.AuthFailureReason
import eu.kanade.tachiyomi.data.social.matrix.SocialError
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind

/**
 * P2-09b: production [PushRegistrar] wired to the Rust Matrix SDK.
 *
 * [appId] must be stable across releases (e.g. the applicationId).
 * [deviceDisplayName] is the human device name (Build.MODEL).
 * [lang] follows BCP-47 (e.g. "fr", "en").
 *
 * `profileTag` is empty: Shiro uses a single profile per device.
 * `append = false`: a new registration replaces any previous one.
 */
class RustPushRegistrar(
    private val clientProvider: () -> Client?,
    private val appId: String,
    private val deviceDisplayName: String,
    private val lang: String,
) : PushRegistrar {

    @Volatile
    private var registeredEndpoint: String? = null

    override suspend fun register(endpoint: String): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()

        return try {
            val identifiers = PusherIdentifiers(appId = appId, pushkey = endpoint)
            val data = HttpPusherData(
                url = endpoint,
                format = PushFormat.EVENT_ID_ONLY,
                defaultPayload = "",
            )
            val kind = PusherKind.Http(data)
            client.setPusher(
                identifiers,
                kind,
                appDisplayName,
                deviceDisplayName,
                "",
                lang,
                false,
            )
            registeredEndpoint = endpoint
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "register"))
        }
    }

    override suspend fun unregister(): SocialResult<Unit> {
        val client = clientProvider() ?: return notAuthenticated()
        val endpoint = registeredEndpoint ?: return SocialResult.Success(Unit)

        return try {
            val identifiers = PusherIdentifiers(appId = appId, pushkey = endpoint)
            client.deletePusher(identifiers)
            registeredEndpoint = null
            SocialResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SocialResult.Failure(mapError(t, "unregister"))
        }
    }

    override fun isRegistered(): Boolean = registeredEndpoint != null

    private fun notAuthenticated(): SocialResult.Failure = SocialResult.Failure(
        SocialError.Auth(AuthFailureReason.NOT_ALLOWED, "No Matrix session active"),
    )

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

    private companion object {
        const val appDisplayName = "Shiro"
    }
}
