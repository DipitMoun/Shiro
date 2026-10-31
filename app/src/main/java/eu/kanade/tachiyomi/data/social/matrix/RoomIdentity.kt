package eu.kanade.tachiyomi.data.social.matrix

import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId

/**
 * Room and thread identity for the Matrix social layer (task P2-06, pure model).
 *
 * PHASE-0-SPEC locks the granularity: one room per (author, work) and one Matrix
 * thread per (room, episode). These keys give both a canonical, stable string form
 * so local bindings (`room_binding`, `thread_binding`) and projections never depend
 * on SDK types or on insertion order.
 *
 * Conventions:
 * - Matrix identifiers stay opaque strings, but they are validated here at the model
 *   boundary via [MatrixIds]; a key never holds a malformed id.
 * - The canonical separator is `|`. It cannot appear inside a validated mxid, a
 *   [WorkId] or an [EpisodeKey], so the canonical forms are unambiguous and
 *   round-trip through `parse`.
 * - No Matrix SDK, no JSON, no network in this file.
 */

/**
 * Boundary validation of opaque Matrix identifiers.
 *
 * Accepts the identifier grammar of the Matrix specification:
 * - user id `@localpart:domain`, room id `!opaque:domain`, alias `#localpart:domain`,
 *   where the local part follows the historical grammar `[A-Za-z0-9._=/-]` and the
 *   domain is a hostname with an optional port;
 * - event ids in the legacy `$opaque:domain` form and in the hash form
 *   `$<base64 chars>` (v3/v4, no colon).
 *
 * Deliberately lenient where servers are lenient (uppercase local parts, hash event
 * ids) and strict where ambiguity would corrupt keys (no whitespace, no `|`, no
 * empty parts, max 255 characters). Identifiers that fail here must be rejected
 * before they reach the domain, never silently repaired.
 */
object MatrixIds {
    /** Matrix caps identifier length at 255 characters. */
    const val MAX_IDENTIFIER_LENGTH = 255

    private val LOCALPART = Regex("[A-Za-z0-9._=/-]{1,128}")
    private val DOMAIN = Regex("[A-Za-z0-9.-]+(:[0-9]{1,5})?")
    private val EVENT_OPAQUE = Regex("[A-Za-z0-9._+/=-]{1,200}")
    private val EVENT_HASH = Regex("[A-Za-z0-9_+/=-]{1,128}")

    fun isValidUserId(raw: String): Boolean = isValidSignedId(raw, '@')

    fun isValidRoomId(raw: String): Boolean = isValidSignedId(raw, '!')

    fun isValidRoomAlias(raw: String): Boolean = isValidSignedId(raw, '#')

    fun isValidEventId(raw: String): Boolean {
        if (raw.length !in 2..MAX_IDENTIFIER_LENGTH || !raw.startsWith('$')) return false
        val body = raw.substring(1)
        val colon = body.indexOf(':')
        if (colon >= 0) {
            val opaque = body.substring(0, colon)
            val domain = body.substring(colon + 1)
            return EVENT_OPAQUE.matches(opaque) && DOMAIN.matches(domain)
        }
        return EVENT_HASH.matches(body)
    }

    /** Validates and returns [raw]; throws [IllegalArgumentException] when malformed. */
    fun requireUserId(raw: String): String = require(raw, ::isValidUserId, "user id")

    /** Validates and returns [raw]; throws [IllegalArgumentException] when malformed. */
    fun requireRoomId(raw: String): String = require(raw, ::isValidRoomId, "room id")

    /** Validates and returns [raw]; throws [IllegalArgumentException] when malformed. */
    fun requireEventId(raw: String): String = require(raw, ::isValidEventId, "event id")

    private fun require(raw: String, isValid: (String) -> Boolean, label: String): String {
        check(isValid(raw)) { "Not a valid Matrix $label: $raw" }
        return raw
    }

    private fun isValidSignedId(raw: String, sigil: Char): Boolean {
        if (raw.length !in 3..MAX_IDENTIFIER_LENGTH || !raw.startsWith(sigil)) return false
        val colon = raw.indexOf(':')
        // The colon must leave at least one character on both sides.
        if (colon < 2 || colon == raw.lastIndex) return false
        return LOCALPART.matches(raw.substring(1, colon)) && DOMAIN.matches(raw.substring(colon + 1))
    }
}

/**
 * Canonical identity of one social room: the author's room for one work
 * (PHASE-0-SPEC §4.1). Two friends publishing moments for the same work always
 * land in two distinct rooms; nothing is ever mirrored between them (§5.1).
 */
data class SocialRoomKey(
    /** Room owner mxid, e.g. `@alice:matrix.org`; not necessarily the current user. */
    val authorMxid: String,
    val workId: WorkId,
) {
    init {
        require(MatrixIds.isValidUserId(authorMxid)) { "Invalid author mxid: $authorMxid" }
    }

    /** Canonical stable string form: `<authorMxid>|<workId>`. */
    val value: String
        get() = "$authorMxid$SEPARATOR${workId.value}"

    override fun toString(): String = value

    /**
     * Room name per §4.1: `<work.title> — <author displayName>`, with the locked
     * fallback `Shiro — <workId>` whenever either part is missing or blank. The
     * fallback carries no episode title and no spoiler.
     */
    fun roomName(workTitle: String?, authorDisplayName: String?): String {
        val title = workTitle?.takeIf { it.isNotBlank() }
        val author = authorDisplayName?.takeIf { it.isNotBlank() }
        return if (title != null && author != null) {
            "$title — $author"
        } else {
            "Shiro — ${workId.value}"
        }
    }

    /** Room topic per §4.1: `Shiro · <workId> · fil de <owner_mxid>`. */
    fun roomTopic(): String = "Shiro · ${workId.value} · fil de $authorMxid"

    /**
     * Whether this room belongs to [currentUserMxid] or to a friend. Comparison is
     * exact: Matrix identifiers are case-sensitive, so `@Alice:matrix.org` is never
     * treated as the same account as `@alice:matrix.org`.
     */
    fun ownershipFor(currentUserMxid: String): ThreadOwnership {
        require(MatrixIds.isValidUserId(currentUserMxid)) {
            "Invalid current user mxid: $currentUserMxid"
        }
        return if (currentUserMxid == authorMxid) ThreadOwnership.OWN else ThreadOwnership.FRIEND
    }

    /**
     * Room alias proposal. Always `null` by design, per §4.1: "Pas d'alias public,
     * pas d'annuaire, pas de `m.room.canonical_alias`". A deterministic localpart
     * derived from (mxid, workId) could also collide across homeservers or leak the
     * work identity into a server-visible directory, and the spec prefers no alias
     * over an unguaranteed one. Rooms are reached through `room_binding`, never
     * through aliases.
     */
    fun proposeRoomAlias(): String? = null

    companion object {
        /** Canonical separator; excluded from every validated component. */
        const val SEPARATOR = "|"

        /** Inverse of [value]; returns null when any component is malformed. */
        fun parse(raw: String): SocialRoomKey? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != 2) return null
            if (!MatrixIds.isValidUserId(parts[0])) return null
            val workId = WorkId.parse(parts[1]) ?: return null
            return SocialRoomKey(parts[0], workId)
        }
    }
}

/**
 * Canonical identity of one episode thread inside a room (PHASE-0-SPEC §4.4).
 *
 * The thread root event id is intentionally *not* part of the key: a redacted root
 * may be replaced by a new root for the same `(room, episode_key)` while moments
 * stay logically attached, so identity must survive root re-creation. The mapping
 * to the current root event id lives in `thread_binding`.
 */
data class SocialThreadKey(
    val room: SocialRoomKey,
    val episodeKey: EpisodeKey,
) {
    /** Canonical stable string form: `<authorMxid>|<workId>|<episodeKey>`. */
    val value: String
        get() = "${room.value}${SocialRoomKey.SEPARATOR}${episodeKey.value}"

    override fun toString(): String = value

    val authorMxid: String get() = room.authorMxid
    val workId: WorkId get() = room.workId

    /** Ownership delegate; see [SocialRoomKey.ownershipFor]. */
    fun ownershipFor(currentUserMxid: String): ThreadOwnership = room.ownershipFor(currentUserMxid)

    companion object {
        /** Inverse of [value]; returns null when any component is malformed. */
        fun parse(raw: String): SocialThreadKey? {
            val parts = raw.split(SocialRoomKey.SEPARATOR)
            if (parts.size != 3) return null
            val room = SocialRoomKey.parse(parts[0] + SocialRoomKey.SEPARATOR + parts[1]) ?: return null
            val episodeKey = EpisodeKey.parse(parts[2]) ?: return null
            return SocialThreadKey(room, episodeKey)
        }
    }
}

/** Whose audience a room/thread writes into: the current user's own, or a friend's. */
enum class ThreadOwnership {
    /** The current user is the author; publishing and pinning are allowed here. */
    OWN,

    /** A friend is the author; replying stays in their audience (§5.1), never mirrored. */
    FRIEND,
    ;

    val isOwn: Boolean get() = this == OWN
}

/**
 * Deterministic wire names locked by PHASE-0-SPEC. Constants only: JSON encoding
 * lives in [MomentPayloadCodec] and in the future room/state codecs, never here.
 */
object SocialSchemaKeys {
    /** State event describing the room itself (§4.2), `state_key = ""`. */
    const val ROOM_STATE_TYPE = "org.shiro.room"

    /** Content field of an `m.room.message` carrying a [MomentPayload] (§3). */
    const val MOMENT_FIELD = "org.shiro.moment"

    /** Content field marking a lazily created episode thread root (§4.4). */
    const val THREAD_ROOT_FIELD = "org.shiro.thread"

    /** In-thread event listing pinned key moments (§6.2); power level 100. */
    const val PINS_EVENT_TYPE = "org.shiro.pins"

    /** Relation type of every moment/reply towards its episode thread root. */
    const val THREAD_REL_TYPE = "m.thread"

    /** Version carried by every `org.shiro.*` payload; unknown versions fall back to `body`. */
    const val SCHEMA_VERSION = 1

    /**
     * Deterministic plain-text body of an episode thread root (§4.4), readable
     * without Shiro: `Fil · <work label> · <episodeKey>`. Falls back to the
     * canonical work id when no title is available; never contains an episode
     * title or any spoiler.
     */
    fun threadRootBody(workTitle: String?, workId: WorkId, episodeKey: EpisodeKey): String {
        val label = workTitle?.takeIf { it.isNotBlank() } ?: workId.value
        return "Fil · $label · ${episodeKey.value}"
    }
}