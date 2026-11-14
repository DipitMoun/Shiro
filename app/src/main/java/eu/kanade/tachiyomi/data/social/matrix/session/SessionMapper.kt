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
    // Existing installs may have persisted NONE before native sliding sync was
    // enabled; the client is built with NATIVE, so restore must match it.
    slidingSyncVersion = SlidingSyncVersion.NATIVE,
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