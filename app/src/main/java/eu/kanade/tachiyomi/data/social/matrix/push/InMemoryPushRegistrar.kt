package eu.kanade.tachiyomi.data.social.matrix.push

import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * In-memory [PushRegistrar] used by P2-10 (UI) and unit tests.
 *
 * State is a single optional endpoint. [register] replaces any previous
 * value; [unregister] clears it (idempotent).
 */
class InMemoryPushRegistrar : PushRegistrar {

    @Volatile
    var currentEndpoint: String? = null
        private set

    var lastRegisterResult: SocialResult<Unit> = SocialResult.Success(Unit)
    var lastUnregisterResult: SocialResult<Unit> = SocialResult.Success(Unit)

    override suspend fun register(endpoint: String): SocialResult<Unit> {
        if (lastRegisterResult is SocialResult.Success) {
            currentEndpoint = endpoint
        }
        return lastRegisterResult
    }

    override suspend fun unregister(): SocialResult<Unit> {
        if (lastUnregisterResult is SocialResult.Success) {
            currentEndpoint = null
        }
        return lastUnregisterResult
    }

    override fun isRegistered(): Boolean = currentEndpoint != null
}
