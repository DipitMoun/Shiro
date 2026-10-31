package eu.kanade.tachiyomi.ui.browse.catalog

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceAnimeSource
import eu.kanade.tachiyomi.data.multisource.MultiSourceDebugLog
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import eu.kanade.tachiyomi.data.telegram.source.TelegramAnimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import kotlin.math.roundToInt

/**
 * Résout un épisode précis sur les sources installées.
 *
 * On ne parcourt jamais toute l'œuvre : on cherche le titre sur chaque source
 * au moment où l'utilisateur demande cet épisode, puis on ne garde que les
 * sources qui possèdent réellement cet épisode.
 */
class CatalogSourceResolver(
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val savedStore: TelegramSavedWorkStore = Injekt.get(),
) {
    private val semaphore = Semaphore(4)
    private val coverageSemaphore = Semaphore(6)

    suspend fun find(work: CatalogWork, episode: Int): List<CatalogSourceMatch> =
        withContext(Dispatchers.IO) {
            val sources = enabledSources()

            val extensionMatches = Collections.synchronizedList(mutableListOf<CatalogSourceMatch>())
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                coroutineScope {
                    sources.forEach { source ->
                        launch {
                            semaphore.withPermit {
                                val match = runCatching {
                                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                        resolveSource(source, work, episode)
                                    }
                                }.getOrNull()
                                if (match != null) extensionMatches.add(match)
                            }
                        }
                    }
                }
            }

            val telegramMatches = resolveTelegram(work, episode)

            (extensionMatches.toList() + telegramMatches)
                .distinctBy { it.animeId ?: "${it.sourceId}:${it.sourceAnime?.url}" }
                .sortedWith(compareBy({ it.isTelegram }, { it.sourceName.lowercase() }))
        }

    /**
     * Cartographie la couverture de toutes les extensions pour une œuvre.
     * Aucune vidéo n'est résolue : uniquement les listes d'épisodes.
     */
    suspend fun coverage(
        work: CatalogWork,
        selectedSourceIds: Set<Long>? = null,
        prioritySourceIds: Set<Long> = emptySet(),
    ): List<SourceCoverage> =
        withContext(Dispatchers.IO) {
            val sources = enabledSources()
                .filter {
                    it.id != MultiSourceAnimeSource.ID &&
                        it.id != LocalAnimeSource.ID &&
                        it.id != TelegramAnimeSource.ID &&
                        (selectedSourceIds == null || it.id in selectedSourceIds)
                }
                .sortedWith(
                    compareByDescending<AnimeSource> { it.id in prioritySourceIds }
                        .thenBy { it.name.lowercase() },
                )

            MultiSourceDebugLog.log(
                "mapping",
                "coverage start work=${work.title} sources=${sources.size} " +
                    "priority=${sources.count { it.id in prioritySourceIds }}",
            )

            val coverages = Collections.synchronizedList(mutableListOf<SourceCoverage>())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val jobs = sources.map { source ->
                scope.launch {
                    coverageSemaphore.withPermit {
                        val startedAt = System.currentTimeMillis()
                        var failure: Throwable? = null
                        val coverage = runCatching {
                            withTimeoutOrNull(COVERAGE_SOURCE_TIMEOUT_MS) {
                                resolveCoverage(source, work)
                            }
                        }
                            .onFailure { failure = it }
                            .getOrNull()
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (coverage != null) {
                            coverages.add(coverage)
                            MultiSourceDebugLog.log(
                                "mapping",
                                "coverage ${source.name}=${coverage.episodes.size} episodes ms=$elapsed",
                            )
                        } else {
                            val reason = failure
                                ?.let { "${it.javaClass.simpleName}: ${it.message}" }
                                ?: "timeout/no-match"
                            MultiSourceDebugLog.log(
                                "mapping",
                                "coverage ${source.name}=none reason=$reason ms=$elapsed",
                            )
                        }
                    }
                }
            }

            val deadline = System.currentTimeMillis() + COVERAGE_TOTAL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && jobs.any { it.isActive }) {
                delay(250)
            }
            val unfinished = jobs.count { it.isActive }
            jobs.forEach { it.cancel() }
            scope.cancel()
            MultiSourceDebugLog.log(
                "mapping",
                "coverage finish work=${work.title} found=${coverages.size} unfinished=$unfinished",
            )
            coverages.toList()
        }

    private fun enabledSources(): List<AnimeSource> {
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabledSources = sourcePreferences.disabledAnimeSources().get()
        return sourceManager.getAll()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
    }

    private suspend fun resolveCoverage(
        source: AnimeSource,
        work: CatalogWork,
    ): SourceCoverage? {
        val latinSynonyms = work.synonyms.filter(::isLatinScript)
        val frenchSynonyms = latinSynonyms.filter { FRENCH_HINT.containsMatchIn(it) }
        val otherSynonyms = latinSynonyms.filterNot { it in frenchSynonyms }
        val queries = (
            listOf(work.title) +
                frenchSynonyms +
                otherSynonyms +
                listOfNotNull(work.originalTitle?.takeIf(::isLatinScript)) +
                work.synonyms
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SEARCH_QUERIES)

        for (query in queries) {
            val page = runCatching {
                source.getSearchAnime(1, query, source.getFilterList())
            }.getOrNull() ?: continue

            val anime = page.animes
                .filter { seasonCompatible(it.title, work) }
                .maxByOrNull { titleScore(it.title, work) }
                ?: continue
            if (titleScore(anime.title, work) < MIN_TITLE_SCORE) continue

            val episodes = runCatching {
                source.getAnimeEpisodeUpdate(
                    anime = anime,
                    episodes = emptyList(),
                    fetchDetails = false,
                    fetchEpisodes = true,
                ).episodes
            }.getOrNull() ?: continue

            val byNumber = episodes.mapNotNull { episode ->
                val number = episode.episode_number
                if (number > 0f) number.roundToInt() to episode else null
            }.toMap()
            if (byNumber.isEmpty()) continue

            return SourceCoverage(
                sourceId = source.id,
                sourceName = source.name,
                sourceAnime = anime,
                episodes = byNumber,
            )
        }
        return null
    }

    private suspend fun resolveSource(
        source: eu.kanade.tachiyomi.animesource.AnimeSource,
        work: CatalogWork,
        episode: Int,
    ): CatalogSourceMatch? {
        val latinSynonyms = work.synonyms.filter(::isLatinScript)
        val frenchSynonyms = latinSynonyms.filter { FRENCH_HINT.containsMatchIn(it) }
        val otherSynonyms = latinSynonyms.filterNot { it in frenchSynonyms }
        val queries = (
            listOf(work.title) +
                frenchSynonyms +
                otherSynonyms +
                listOfNotNull(work.originalTitle?.takeIf(::isLatinScript)) +
                work.synonyms
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SEARCH_QUERIES)

        for (query in queries) {
            val page = runCatching {
                source.getSearchAnime(1, query, source.getFilterList())
            }.getOrNull() ?: continue

            val compatibleAnimes = page.animes.filter { candidate ->
                seasonCompatible(candidate.title, work)
            }
            val anime = compatibleAnimes.maxByOrNull { candidate ->
                titleScore(candidate.title, work)
            } ?: continue
            if (titleScore(anime.title, work) < MIN_TITLE_SCORE) continue

            val episodes = runCatching {
                source.getAnimeEpisodeUpdate(
                    anime = anime,
                    episodes = emptyList(),
                    fetchDetails = false,
                    fetchEpisodes = true,
                ).episodes
            }.getOrNull() ?: continue
            val target = episodes.firstOrNull { candidate ->
                val number = candidate.episode_number
                number > 0f && number.roundToInt() == episode
            } ?: continue

            return CatalogSourceMatch(
                sourceName = source.name,
                animeTitle = anime.title,
                episodeName = target.name,
                sourceId = source.id,
                sourceAnime = anime,
                isTelegram = false,
            )
        }
        return null
    }

    private fun resolveTelegram(work: CatalogWork, episode: Int): List<CatalogSourceMatch> {
        return savedStore.snapshot()
            .asSequence()
            .filter { it.kind == TelegramSavedKind.VIDEO && it.libraryId != null }
            .filter { titleScore(it.title, work) >= MIN_TITLE_SCORE }
            .filter { seasonCompatible(it.title, work) }
            .filter { saved ->
                saved.files.any { file ->
                    val start = file.episode
                    start != null && episode >= start && episode <= (file.episodeEnd ?: start)
                } ||
                    saved.files.singleOrNull()?.episode == null
            }
            .mapNotNull { saved ->
                val libraryId = saved.libraryId ?: return@mapNotNull null
                val file = saved.files.firstOrNull { candidate ->
                    val start = candidate.episode
                    start != null && episode >= start && episode <= (candidate.episodeEnd ?: start)
                } ?: saved.files.singleOrNull()?.takeIf { it.episode == null }
                CatalogSourceMatch(
                    animeId = libraryId,
                    sourceName = "Telegram · ${saved.title}",
                    animeTitle = saved.title,
                    episodeName = file?.fileName ?: "Épisode $episode",
                    isTelegram = true,
                )
            }
            .toList()
    }

    private fun titleScore(sourceTitle: String, work: CatalogWork): Double {
        var score = maxOf(
            similarity(sourceTitle, work.title),
            work.originalTitle?.let { similarity(sourceTitle, it) } ?: 0.0,
        )
        for (synonym in work.synonyms) {
            score = maxOf(score, similarity(sourceTitle, synonym))
        }
        return score
    }

    /**
     * Évite de proposer la saison 2 quand l'utilisateur a demandé la saison 1
     * (ou inversement). Les titres de sources contiennent souvent « 2 »,
     * « Saison 2 » ou « 2nd Season ».
     */
    private fun seasonCompatible(sourceTitle: String, work: CatalogWork): Boolean {
        val sourceSeasons = seasonNumbers(sourceTitle)
        if (sourceSeasons.isEmpty()) return true

        val workSeasons = buildSet {
            addAll(seasonNumbers(work.title))
            addAll(seasonNumbers(work.originalTitle))
            work.synonyms.forEach { addAll(seasonNumbers(it)) }
        }

        if (workSeasons.isEmpty()) {
            // Le catalogue ne précise pas de saison : on considère qu'il s'agit
            // de la première et on refuse une source explicitement plus récente.
            return sourceSeasons.all { it <= 1 }
        }
        return sourceSeasons.any { it in workSeasons }
    }

    private fun seasonNumbers(value: String?): Set<Int> {
        if (value.isNullOrBlank()) return emptySet()
        val seasons = mutableSetOf<Int>()

        EXPLICIT_SEASON.findAll(value).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let(seasons::add)
        }
        NTH_SEASON.findAll(value).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let(seasons::add)
        }
        SHORT_SEASON.findAll(value).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let(seasons::add)
        }

        val withoutBrackets = value
            .replace(BRACKETED_CONTENT, " ")
            .trim()
        TRAILING_NUMBER.find(withoutBrackets)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 2..9 }
            ?.let(seasons::add)

        return seasons
    }

    private fun similarity(a: String, b: String): Double {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) return 0.85

        val leftTokens = left.split(' ').filter { it.length > 1 }.toSet()
        val rightTokens = right.split(' ').filter { it.length > 1 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0

        val intersection = leftTokens.intersect(rightTokens).size
        val union = leftTokens.union(rightTokens).size
        return intersection.toDouble() / union
    }

    private fun normalize(value: String): String {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(java.util.Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()
    }

    private fun isLatinScript(value: String): Boolean {
        return value.any { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '\u00C0'..'\u00FF'
        }
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val COVERAGE_SOURCE_TIMEOUT_MS = 30_000L
        const val COVERAGE_TOTAL_TIMEOUT_MS = 60_000L
        const val MIN_TITLE_SCORE = 0.45
        const val MAX_SEARCH_QUERIES = 4
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val FRENCH_HINT = Regex(
            "(?i)\\b(saison|partie|vf|vostfr)\\b|\\b[ld]['’]",
        )
        val EXPLICIT_SEASON = Regex("(?i)\\b(?:season|saison|s)\\.?\\s*(\\d{1,2})\\b")
        val NTH_SEASON = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b")
        val SHORT_SEASON = Regex("(?i)(?:^|\\s)S(\\d{1,2})(?:\\s|$)")
        val BRACKETED_CONTENT = Regex("\\([^)]*\\)|\\[[^]]*]")
        val TRAILING_NUMBER = Regex("(?i)\\s(\\d{1,2})$")
    }
}
