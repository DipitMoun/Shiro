package eu.kanade.tachiyomi.data.social.matrix.push

import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * P2-09b: SDK-free contract to register/unregister a Matrix HTTP pusher
 * pointing at a UnifiedPush endpoint (ntfy, etc.).
 *
 * The endpoint string is a capability URL produced by UnifiedPush
 * (typically `https://ntfy.sh/<topic>`). It must never be logged.
 *
 * One pusher per device, keyed by `(appId, pushkey=endpoint)`. Registering
 * twice replaces the previous one. Unregistering is idempotent.
 */
interface PushRegistrar {

    /**
     * Registers or replaces the pusher for [endpoint].
     * The server-visible app_id is fixed by the implementation.
     */
    suspend fun register(endpoint: String): SocialResult<Unit> = register(pushkey = endpoint, gatewayUrl = endpoint)

    /**
     * Registers or replaces a pusher whose [pushkey] differs from the gateway URL, as with
     * Firebase: the pushkey is the FCM registration token, [gatewayUrl] the Matrix push
     * gateway notify endpoint that relays to FCM.
     */
    suspend fun register(pushkey: String, gatewayUrl: String): SocialResult<Unit>

    /** Removes any pusher previously registered for this device. */
    suspend fun unregister(): SocialResult<Unit>

    /** True when a pusher is currently registered (local view). */
    fun isRegistered(): Boolean
}
