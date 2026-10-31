package eu.kanade.tachiyomi.ui.browse.catalog

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable

/** Bloc de découverte AniList (tendances, populaires, mieux notés). */
data class DiscoverFeed(
    val trending: List<CatalogWork> = emptyList(),
    val popular: List<CatalogWork> = emptyList(),
    val topRated: List<CatalogWork> = emptyList(),
) {
    val isEmpty: Boolean
        get() = trending.isEmpty() && popular.isEmpty() && topRated.isEmpty()
}

/** Élément du carrousel avec son libellé éditorial. */
data class DiscoverItem(
    val work: CatalogWork,
    val label: String,
)

/** Couverture d'une extension pour une œuvre multi-source. */
data class SourceCoverage(
    val sourceId: Long,
    val sourceName: String,
    val sourceAnime: SAnime,
    val episodes: Map<Int, SEpisode>,
)

/** Œuvre issue d'un catalogue externe (AniList, Kitsu, MAL/Jikan). */
@Serializable
data class CatalogWork(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val synonyms: List<String> = emptyList(),
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val synopsis: String? = null,
    val year: Int? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val genres: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
) {
    val providerLabel: String
        get() = providers.joinToString(" · ")
}

private val FRENCH_STRONG = Regex(
    "(?i)\\b(saison|partie|vf|vostfr)\\b|\\b[ld]['’]",
)
private val FRENCH_WORDS = Regex(
    "(?i)\\b(le|la|les|des|du|de|une|un|et|pour|avec|dans|sur|au|aux|finale|film)\\b",
)
private val NON_FRENCH_HINT = Regex(
    "(?i)\\b(del|della|delle|dello|dei|degli|il|lo|gli|el|los|las|y|con|para|por|oltre|viaggio|fine)\\b",
)

/**
 * Titre français venant des synonymes AniList quand il existe.
 *
 * AniList ne fournit pas la langue des synonymes : on privilégie les tournures
 * françaises (L', Saison, Partie…) et on écarte les tournures italiennes ou
 * espagnoles les plus courantes.
 */
fun CatalogWork.preferredTitle(): String {
    return synonyms
        .filterNot { NON_FRENCH_HINT.containsMatchIn(it) }
        .map { synonym ->
            val strongCount = FRENCH_STRONG.findAll(synonym).count()
            val wordCount = FRENCH_WORDS.findAll(synonym)
                .map { it.value.lowercase() }
                .distinct()
                .count()
            val score = strongCount * 2 + wordCount
            Triple(synonym, score, strongCount > 0 || wordCount >= 2)
        }
        .filter { it.third }
        .maxByOrNull { it.second }
        ?.first
        ?: title
}

/** Source qui propose un épisode précis pour une œuvre du catalogue. */
data class CatalogSourceMatch(
    val sourceName: String,
    val animeTitle: String? = null,
    val episodeName: String?,
    val animeId: Long? = null,
    val sourceId: Long? = null,
    val sourceAnime: SAnime? = null,
    val isTelegram: Boolean = false,
)
