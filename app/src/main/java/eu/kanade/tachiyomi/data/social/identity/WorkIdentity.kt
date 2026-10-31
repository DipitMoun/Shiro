package eu.kanade.tachiyomi.data.social.identity

import java.math.BigDecimal

/**
 * Canonical work identity used by the Matrix social layer.
 *
 * A work never gets merged by title, year or duration. Resolution is exact and
 * fail-closed: if no shared scheme/id alias exists, the works stay separate.
 */
enum class WorkScheme(val wireName: String, val priority: Int) {
    ANILIST("anilist", 0),
    MAL("mal", 1),
    ANIDB("anidb", 2),
    TVDB("tvdb", 2),
    TELEGRAM("tg", 3),
    LOCAL("local", 4),
    ;

    companion object {
        fun fromWireName(value: String): WorkScheme? = entries.firstOrNull { it.wireName == value }
    }
}

data class WorkId private constructor(
    val scheme: WorkScheme,
    val remoteId: String,
) {
    val value: String
        get() = "${scheme.wireName}:$remoteId"

    override fun toString(): String = value

    companion object {
        private val REMOTE_ID = Regex("[A-Za-z0-9._:-]{1,128}")

        fun parse(raw: String): WorkId? {
            val separator = raw.indexOf(':')
            if (separator <= 0 || separator == raw.lastIndex) return null
            val scheme = WorkScheme.fromWireName(raw.substring(0, separator)) ?: return null
            val remoteId = raw.substring(separator + 1)
            if (!REMOTE_ID.matches(remoteId)) return null
            return WorkId(scheme, remoteId)
        }

        /**
         * Returns the best canonical id from a set of aliases, without using
         * title-based heuristics. Returns null when aliases are empty/invalid.
         */
        fun fromAliases(aliases: Map<String, String>): WorkId? {
            val parsed = aliases.mapNotNull { (scheme, remoteId) ->
                val wire = WorkScheme.fromWireName(scheme) ?: return@mapNotNull null
                if (!REMOTE_ID.matches(remoteId)) return@mapNotNull null
                WorkId(wire, remoteId)
            }
            return parsed.minByOrNull { it.scheme.priority }
        }
    }
}

enum class WorkFormat {
    TV,
    MOVIE,
    OVA,
    ONA,
    SPECIAL,
    OTHER,
}

data class WorkIdentity(
    val id: WorkId,
    val ids: Map<String, String> = emptyMap(),
    val title: String? = null,
    val format: WorkFormat? = null,
    val year: Int? = null,
) {
    init {
        require(ids.values.all { it.isNotBlank() }) { "Alias values must not be blank" }
        require(title == null || title.length in 1..200) { "Title must be 1..200 characters" }
        require(year == null || year in 1900..2200) { "Year is outside the supported range" }
    }

    val canonicalAliases: Map<WorkScheme, String>
        get() = (ids + (id.scheme.wireName to id.remoteId))
            .mapNotNull { (scheme, remoteId) ->
                WorkScheme.fromWireName(scheme)?.let { it to remoteId }
            }
            .toMap()
}

data class AliasKey(
    val scheme: WorkScheme,
    val remoteId: String,
)

interface WorkAliasStore {
    fun resolve(aliases: Map<String, String>): WorkId?

    fun register(canonical: WorkId, aliases: Map<String, String>)

    fun aliasesFor(canonical: WorkId): Map<String, String>
}

class InMemoryWorkAliasStore : WorkAliasStore {
    private val byAlias = mutableMapOf<AliasKey, WorkId>()
    private val byCanonical = mutableMapOf<WorkId, MutableMap<AliasKey, String>>()

    override fun resolve(aliases: Map<String, String>): WorkId? {
        val matches = aliases.mapNotNull { (scheme, remoteId) ->
            val wire = WorkScheme.fromWireName(scheme) ?: return@mapNotNull null
            byAlias[AliasKey(wire, remoteId)]
        }.toSet()
        return matches.singleOrNull()
    }

    override fun register(canonical: WorkId, aliases: Map<String, String>) {
        val entries = (aliases + (canonical.scheme.wireName to canonical.remoteId))
            .mapNotNull { (scheme, remoteId) ->
                val wire = WorkScheme.fromWireName(scheme) ?: return@mapNotNull null
                AliasKey(wire, remoteId) to remoteId
            }
        val target = byCanonical.getOrPut(canonical) { mutableMapOf() }
        entries.forEach { (key, remoteId) ->
            byAlias[key] = canonical
            target[key] = remoteId
        }
    }

    override fun aliasesFor(canonical: WorkId): Map<String, String> =
        byCanonical[canonical]
            ?.entries
            ?.associate { (key, remoteId) -> key.scheme.wireName to remoteId }
            .orEmpty()
}

data class EpisodeKey private constructor(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid episode key: $value" }
    }

    companion object {
        private val PATTERN = Regex("^(S[0-9]{2}E[0-9]{3,4}(\\.[0-9]{1,2})?|MAIN)$")
        val MAIN = EpisodeKey("MAIN")

        fun parse(value: String): EpisodeKey? =
            if (PATTERN.matches(value)) EpisodeKey(value) else null

        fun of(season: Int, number: Double): EpisodeKey {
            require(season in 0..99) { "Season must be between 0 and 99" }
            require(number >= 0.0 && number.isFinite()) { "Episode number must be finite and positive" }

            val numberText = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
            val whole = numberText.substringBefore('.')
            val fraction = numberText.substringAfter('.', "")
            val width = if (whole.length <= 3) 3 else 4
            val paddedWhole = whole.padStart(width, '0')
            val formatted = if (fraction.isEmpty()) paddedWhole else "$paddedWhole.$fraction"
            return EpisodeKey("S${season.toString().padStart(2, '0')}E$formatted")
        }
    }
}

enum class EpisodeKind {
    REGULAR,
    SPECIAL,
    FILM,
    OVA,
    RECAP,
    UNKNOWN,
}

enum class EpisodeNumbering {
    SEASON,
    ABSOLUTE,
    UNKNOWN,
}

data class EpisodeIdentity(
    val key: EpisodeKey,
    val season: Int? = null,
    val number: Double? = null,
    val kind: EpisodeKind = EpisodeKind.UNKNOWN,
    val numbering: EpisodeNumbering = EpisodeNumbering.UNKNOWN,
    val title: String? = null,
) {
    init {
        require(season == null || season >= 0) { "Season must be positive" }
        require(number == null || number >= 0.0) { "Episode number must be positive" }
        require(title == null || title.length <= 200) { "Episode title is too long" }
    }
}
