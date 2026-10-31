package eu.kanade.tachiyomi.data.social.matrix.session

/**
 * Serialisable snapshot of an authenticated Matrix session (P2-05c).
 *
 * Mirrors the fields of the Rust SDK `Session` class, but stores them as primitives
 * so the credential store never depends on `org.matrix.rustcomponents.sdk.*`. The
 * SDK-facing adapter translates back and forth in `RustSessionDelegate`.
 *
 * `slidingSyncVersion` is a string ("NONE"/"NATIVE") rather than an enum to keep
 * this type stable across minor SDK upgrades.
 */
data class PersistedSession(
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val displayName: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val oauthData: String? = null,
    val slidingSyncVersion: String = "NONE",
) {
    val account: SocialAccountView
        get() = SocialAccountView(
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            displayName = displayName,
        )
}

/**
 * Minimal social account view decoupled from `SocialAccount` so this file stays
 * importable without the parent package's contracts during early bring-up.
 * Mapped to `SocialAccount` by the session manager.
 */
data class SocialAccountView(
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val displayName: String? = null,
)