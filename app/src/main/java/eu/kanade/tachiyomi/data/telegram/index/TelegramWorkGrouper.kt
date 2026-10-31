package eu.kanade.tachiyomi.data.telegram.index

import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser

/**
 * Regroupement des fichiers d'un canal, sans dépendance Android.
 *
 * Trois règles :
 * 1. même signature de titre (articles et ponctuation ignorés) ;
 * 2. fusion prudente des signatures quasi identiques (un mot d'écart) ;
 * 3. saison déduite seulement si elle est unique et que la numérotation reste
 *    compatible.
 */
object TelegramWorkGrouper {

    /** Jaccard minimal : un mot d'écart sur six tokens. */
    const val SIMILARITY_THRESHOLD = 0.82

    /** Au-delà, la fusion approximative est abandonnée pour rester linéaire. */
    const val MAX_PAIRWISE_SIGNATURES = 2_000

    /** Écart toléré entre un numéro sans saison et la saison explicite. */
    const val SEASON_EPISODE_TOLERANCE = 2

    data class Input(
        val fileId: Int,
        val title: String,
        val season: Int? = null,
        val number: Int? = null,
        val isDocument: Boolean = false,
    )

    data class Output(
        val fileId: Int,
        val title: String,
        val season: Int?,
    )

    fun group(files: List<Input>): List<Output> {
        val result = mutableListOf<Output>()

        files.groupBy { it.isDocument }.values.forEach { kindFiles ->
            cluster(kindFiles).forEach { members ->
                val explicitSeasons = members.mapNotNull { it.season }.distinct()
                val inferredSeason = explicitSeasons.singleOrNull()
                val maxNumber = members
                    .filter { it.season != null }
                    .mapNotNull { it.number }
                    .maxOrNull()
                val title = canonicalTitle(members.map { it.title })

                members.groupBy { member ->
                    when {
                        member.isDocument -> null
                        member.season != null -> member.season
                        else -> inferredSeasonFor(member, inferredSeason, maxNumber)
                    }
                }.forEach { (season, group) ->
                    group.forEach { result += Output(it.fileId, title, season) }
                }
            }
        }

        return result
    }

    /**
     * Fusion prudente : deux signatures ne sont réunies que si elles partagent
     * presque tous leurs tokens.
     */
    private fun cluster(entries: List<Input>): List<List<Input>> {
        val bySignature = entries.groupBy { TelegramTitleParser.signature(it.title) }
        val signatures = bySignature.keys.toList()
        if (signatures.size <= 1) return listOf(entries)

        // Un canal de documents peut contenir des dizaines de milliers de
        // signatures : la comparaison Jaccard deux à deux y devient
        // inutilisable (O(n²)). On garde alors les groupes de signatures
        // exactes, sans fusion approximative, plutôt que de bloquer le rebuild.
        if (signatures.size > MAX_PAIRWISE_SIGNATURES) {
            return signatures.map { bySignature.getValue(it) }
        }

        val parents = IntArray(signatures.size) { it }

        fun find(index: Int): Int {
            var current = index
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }

        for (left in signatures.indices) {
            for (right in left + 1 until signatures.size) {
                if (sameWork(signatures[left], signatures[right])) {
                    val first = find(left)
                    val second = find(right)
                    if (first != second) parents[second] = first
                }
            }
        }

        return signatures.indices
            .groupBy { find(it) }
            .values
            .map { indexes -> indexes.flatMap { bySignature.getValue(signatures[it]) } }
    }

    private fun sameWork(first: String, second: String): Boolean {
        val left = first.split('-').filter { it.isNotBlank() }.toSet()
        val right = second.split('-').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return false

        val common = left.count { it in right }
        val union = left.size + right.size - common
        return common.toDouble() / union >= SIMILARITY_THRESHOLD
    }

    /**
     * Rattache un fichier sans saison à la saison unique du groupe uniquement
     * si son numéro reste dans la plage déjà vue (tolérance de deux épisodes).
     */
    private fun inferredSeasonFor(
        entry: Input,
        inferredSeason: Int?,
        maxNumber: Int?,
    ): Int? {
        if (inferredSeason == null) return null

        val number = entry.number ?: return inferredSeason
        if (maxNumber == null) return inferredSeason

        return inferredSeason.takeIf { number <= maxNumber + SEASON_EPISODE_TOLERANCE }
    }

    /** Variante la plus fréquente ; à fréquence égale, la plus courte puis la mieux typographiée. */
    private fun canonicalTitle(titles: List<String>): String {
        return titles.groupingBy { it }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.any(Char::isLowerCase) }
                    .thenBy { it.key.length },
            )
            .first().key
    }
}
