package eu.kanade.tachiyomi.data.social.matrix.session

import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/**
 * Translates between [PersistedSession] (stable, serialisable) and the Rust SDK
 * `Session` class. Top-level extensions, package-private (`internal`).
 */
internal fun PersistedSession.toSdk(): Session = Session(
    userId = userId,
    deviceId = deviceId,
    accessToken = accessToken,
    homeserverUrl = homeserverUrl,
    oauthData = oauthData,
    refreshToken = refreshToken,
    slidingSyncVersion = slidingSyncVersionFromString(slidingSyncVersion),
)

internal fun Session.toPersisted(displayName: String? = null): PersistedSession =
    PersistedSession(
        userId = userId,
        deviceId = deviceId,
        homeserverUrl = homeserverUrl,
        displayName = displayName,
        accessToken = accessToken,
        refreshToken = refreshToken,
        oauthData = oauthData,
        slidingSyncVersion = slidingSyncVersion.toString(),
    )

private fun slidingSyncVersionFromString(value: String): SlidingSyncVersion = when (value) {
    "NATIVE" -> SlidingSyncVersion.NATIVE
    else     -> SlidingSyncVersion.NONE
}