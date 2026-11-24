package eu.kanade.tachiyomi.data.multisource

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.telegram.source.TelegramMangaSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.browse.catalog.preferredTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

/** Une source d'extension qui contient l'œuvre et ses chapitres numérotés. */
data class MangaSourceCoverage(
    val sourceId: Long,
    val sourceName: String,
    val sourceManga: SManga,
    val chapters: Map<Int, SChapter>,
)

/** Pages résolues et source réelle à utiliser pour les images. */
data class ResolvedMangaPages(
    val source: HttpSource,
    val pages: List<Page>,
)

/**
 * Finds a catalog manga on the installed extensions.
 *
 * Every source that has the work comes back with its chapters; [MultiSourceMangaMapper] turns
 * that into the persisted chapter mapping, the same way the anime mapping works.
 */
class CatalogMangaSourceResolver(
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) {

    /**
     * The sources that have [work], with their chapters. Sources are searched a few at a time;
     * slow ones are abandoned once another already covers the work.
     */
    suspend fun coverages(
        work: CatalogWork,
        selectedSourceIds: Set<Long>?,
        prioritySourceIds: Set<Long> = emptySet(),
    ): List<MangaSourceCoverage> = withContext(Dispatchers.IO) {
        val sources = enabledSources()
            .filter { selectedSourceIds == null || it.id in selectedSourceIds }
            .sortedByDescending { it.id in prioritySourceIds }
        searchSources(work, sources, searchQueries(work))
    }

    /**
     * Searches [sources] for [work] with a few of them at a time. Once one source covers the
     * work the others only get a short grace period; the rest is abandoned rather than awaited.
     */
    private suspend fun searchSources(
        work: CatalogWork,
        sources: List<CatalogueSource>,
        queries: List<String>,
    ): List<MangaSourceCoverage> {
        val expected = work.episodes ?: 0
        val results = java.util.Collections.synchronizedList(mutableListOf<MangaSourceCoverage>())
        val gate = Semaphore(PARALLEL_SOURCES)
        // Own scope: a source stuck in blocking code must not hold the caller back.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = sources.map { source ->
            scope.launch {
                gate.withPermit {
                    val coverage = runCatching {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { resolveCoverage(source, work, queries) }
                    }
                        .onFailure {
                            logcat(LogPriority.WARN, it) {
                                "Multi-source manga : recherche impossible sur ${source.name}"
                            }
                        }
                        .getOrNull()
                    if (coverage != null && coverage.chapters.isNotEmpty()) results.add(coverage)
                }
            }
        }

        val startedAt = System.currentTimeMillis()
        var settledAt = 0L
        while (jobs.any { it.isActive }) {
            delay(POLL_INTERVAL_MS)
            val now = System.currentTimeMillis()
            if (settledAt == 0L) {
                val found = synchronized(results) { results.toList() }
                val settled = if (expected > 0) {
                    found.any { it.chapters.size >= expected * FULL_COVERAGE_RATIO }
                } else {
                    found.isNotEmpty()
                }
                if (settled) settledAt = now
            }
            if (settledAt != 0L) {
                val grace = if (expected > 0) FULL_COVERAGE_GRACE_MS else UNKNOWN_COUNT_GRACE_MS
                if (now - settledAt >= grace) break
            }
            if (now - startedAt >= TOTAL_TIMEOUT_MS) break
        }
        scope.cancel()
        val found = synchronized(results) { results.toList() }
        MultiSourceDebugLog.log(
            "manga",
            "coverage work=${work.title} found=${found.size}/${sources.size} " +
                found.joinToString(" ") { "${it.sourceName}=${it.chapters.size}" },
        )
        return found
    }

    private suspend fun resolveCoverage(
        source: CatalogueSource,
        work: CatalogWork,
        queries: List<String>,
    ): MangaSourceCoverage? {
        val expected = work.episodes ?: 0
        val filters = runCatching { source.getFilterList() }.getOrDefault(FilterList())
        for (query in queries) {
            val page = runCatching {
                source.getSearchManga(1, query, filters)
            }.getOrNull() ?: continue

            val candidates = page.mangas
                .filter { seasonCompatible(it.title, work) }
                .map { it to titleScore(it.title, work) }
                .filter { (_, score) -> score >= MIN_TITLE_SCORE }
                .sortedByDescending { (_, score) -> score }
            if (candidates.isEmpty()) continue
            val bestScore = candidates.first().second

            // The best title is not always the right entry (another season, a spin-off): when it
            // covers too little, the next close candidate gets a chance.
            var chosen: Pair<SManga, Map<Int, SChapter>>? = null
            for ((manga, score) in candidates.take(MAX_CANDIDATES_PER_QUERY)) {
                if (score < bestScore - CANDIDATE_SCORE_MARGIN) break
                val byNumber = fetchChapterMap(source, manga) ?: continue
                if (chosen == null || byNumber.size > chosen.second.size) chosen = manga to byNumber
                if (expected <= 0 || byNumber.size >= expected * ENOUGH_COVERAGE_RATIO) break
            }
            val (manga, byNumber) = chosen ?: continue

            return MangaSourceCoverage(
                sourceId = source.id,
                sourceName = source.name,
                sourceManga = manga,
                chapters = byNumber,
            )
        }
        return null
    }

    /** Chapters of [manga] by number; a whole-number chapter wins over a fractional one. */
    private suspend fun fetchChapterMap(source: CatalogueSource, manga: SManga): Map<Int, SChapter>? {
        val chapters = runCatching {
            source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
        }.getOrNull() ?: return null

        return chapters
            .mapNotNull { chapter -> chapterNumber(chapter)?.let { it to chapter } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (number, group) ->
                group.firstOrNull { it.chapter_number == number.toFloat() } ?: group.first()
            }
            .toSortedMap()
            .takeIf { it.isNotEmpty() }
    }

    private fun enabledSources(): List<CatalogueSource> {
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabled = sourcePreferences.disabledMangaSources().get()
        return sourceManager.getCatalogueSources()
            .filter {
                it.id != LocalMangaSource.ID &&
                    it.id != TelegramMangaSource.ID &&
                    it.id != MultiSourceMangaSource.ID
            }
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabled }
            .sortedBy { it.name.lowercase() }
            .take(MAX_SOURCES)
    }

    private fun searchQueries(work: CatalogWork): List<String> {
        return (
            listOf(work.title, work.preferredTitle()) +
                listOfNotNull(work.originalTitle) +
                work.synonyms
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SEARCH_QUERIES)
    }

    private fun chapterNumber(chapter: SChapter): Int? {
        val number = chapter.chapter_number
        if (number > 0f) return number.roundToInt()
        // Extensions written for extensions-lib 1.6 often leave the number unset: read the name.
        return LABELLED_NUMBER.find(chapter.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: TRAILING_NUMBER.find(chapter.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: FIRST_NUMBER.find(chapter.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun titleScore(sourceTitle: String, work: CatalogWork): Double {
        var score = maxOf(
            similarity(sourceTitle, work.title),
            similarity(sourceTitle, work.preferredTitle()),
            work.originalTitle?.let { similarity(sourceTitle, it) } ?: 0.0,
        )
        for (synonym in work.synonyms) {
            score = maxOf(score, similarity(sourceTitle, synonym))
        }
        return score
    }

    private fun seasonCompatible(sourceTitle: String, work: CatalogWork): Boolean {
        val sourceSeasons = seasonNumbers(sourceTitle)
        if (sourceSeasons.isEmpty()) return true
        val workSeasons = buildSet {
            addAll(seasonNumbers(work.title))
            addAll(seasonNumbers(work.originalTitle))
            work.synonyms.forEach { addAll(seasonNumbers(it)) }
        }
        if (workSeasons.isEmpty()) return sourceSeasons.all { it <= 1 }
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
        val withoutBrackets = value.replace(BRACKETED_CONTENT, " ").trim()
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

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val FULL_COVERAGE_RATIO = 0.95
        const val FULL_COVERAGE_GRACE_MS = 4_000L
        const val UNKNOWN_COUNT_GRACE_MS = 8_000L
        const val MAX_CANDIDATES_PER_QUERY = 3
        const val CANDIDATE_SCORE_MARGIN = 0.15
        const val ENOUGH_COVERAGE_RATIO = 0.6
        const val PARALLEL_SOURCES = 6
        const val POLL_INTERVAL_MS = 100L
        const val MIN_TITLE_SCORE = 0.45
        const val MAX_SEARCH_QUERIES = 4
        const val MAX_SOURCES = 12
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val EXPLICIT_SEASON = Regex("(?i)\\b(?:season|saison|s)\\.?\\s*(\\d{1,2})\\b")
        val NTH_SEASON = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b")
        val SHORT_SEASON = Regex("(?i)(?:^|\\s)S(\\d{1,2})(?:\\s|$)")
        val BRACKETED_CONTENT = Regex("\\([^)]*\\)|\\[[^]]*]")
        val TRAILING_NUMBER = Regex("(?i)\\s(\\d{1,2})$")
        val LABELLED_NUMBER = Regex("(?i)(?:chapter|chapitre|chap\\.?|ch\\.?|ep\\.?|#)\\s*(\\d{1,4})")
        val FIRST_NUMBER = Regex("(\\d{1,4})")
    }
}
