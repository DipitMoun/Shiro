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
 * Résout une œuvre manga du catalogue sur les extensions installées.
 *
 * Le moteur manga n'a pas de mapping persisté : à l'ouverture d'une œuvre, on
 * cherche son titre sur les sources activées, on garde la meilleure
 * correspondance et on réutilise ses chapitres. La correspondance est mise en
 * cache pour la session.
 */
class CatalogMangaSourceResolver(
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val workStore: MultiSourceWorkStore = Injekt.get(),
) {

    private data class CachedCoverage(
        val coverage: MangaSourceCoverage?,
        val at: Long,
    )

    private val cache = mutableMapOf<String, CachedCoverage>()

    suspend fun coverage(work: CatalogWork, workUrl: String? = null): MangaSourceCoverage? =
        withContext(Dispatchers.IO) {
            val preferred = workUrl?.let { workStore.preferredSource(it) }
            val key = "${work.id}#${preferred ?: 0}"
            val now = System.currentTimeMillis()
            synchronized(cache) { cache[key] }
                ?.takeIf { now - it.at < CACHE_TTL_MS }
                ?.let { return@withContext it.coverage }

            val coverage = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { findCoverage(work, preferred) }
            synchronized(cache) { cache[key] = CachedCoverage(coverage, now) }
            coverage
        }

    /** Sources qui contiennent réellement l'œuvre, pour le sélecteur manuel. */
    suspend fun availableSources(work: CatalogWork): List<MangaSourceCoverage> =
        withContext(Dispatchers.IO) {
            searchSources(work, enabledSources(), searchQueries(work), graceAfterFirstMs = null)
                .sortedByDescending { it.chapters.size }
        }

    fun invalidate(work: CatalogWork) {
        synchronized(cache) {
            cache.keys.filter { it.startsWith("${work.id}#") }.forEach(cache::remove)
        }
    }

    private suspend fun findCoverage(
        work: CatalogWork,
        preferredSourceId: Long?,
    ): MangaSourceCoverage? {
        val found = searchSources(
            work,
            enabledSources(),
            searchQueries(work),
            graceAfterFirstMs = OTHER_SOURCES_GRACE_MS,
            stopAtSourceId = preferredSourceId,
        )
        // The user's choice wins; otherwise the source with the most chapters.
        val best = found.firstOrNull { it.sourceId == preferredSourceId }
            ?: found.maxByOrNull { it.chapters.size }
        if (best != null) {
            MultiSourceDebugLog.log(
                "manga",
                "coverage work=${work.title} source=${best.sourceName} chapters=${best.chapters.size} " +
                    "candidates=${found.size}",
            )
        }
        return best
    }

    /**
     * Searches [sources] for [work] with a few of them at a time. Slow sources are abandoned
     * instead of awaited: with [graceAfterFirstMs] the search ends shortly after the first source
     * that has the work, with null it waits for every source. [stopAtSourceId] ends it at once
     * when that source answers.
     */
    private suspend fun searchSources(
        work: CatalogWork,
        sources: List<CatalogueSource>,
        queries: List<String>,
        graceAfterFirstMs: Long?,
        stopAtSourceId: Long? = null,
    ): List<MangaSourceCoverage> {
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
        var firstAt = 0L
        while (jobs.any { it.isActive }) {
            delay(POLL_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val snapshot = synchronized(results) { results.toList() }
            if (snapshot.isNotEmpty() && firstAt == 0L) firstAt = now
            if (stopAtSourceId != null && snapshot.any { it.sourceId == stopAtSourceId }) break
            if (graceAfterFirstMs != null && firstAt != 0L && now - firstAt >= graceAfterFirstMs) break
            if (now - startedAt >= TOTAL_TIMEOUT_MS) break
        }
        scope.cancel()
        return synchronized(results) { results.toList() }
    }

    private suspend fun resolveCoverage(
        source: CatalogueSource,
        work: CatalogWork,
        queries: List<String>,
    ): MangaSourceCoverage? {
        val filters = runCatching { source.getFilterList() }.getOrDefault(FilterList())
        for (query in queries) {
            val page = runCatching {
                source.getSearchManga(1, query, filters)
            }.getOrNull() ?: continue

            val manga = page.mangas
                .filter { seasonCompatible(it.title, work) }
                .maxByOrNull { titleScore(it.title, work) }
                ?: continue
            if (titleScore(manga.title, work) < MIN_TITLE_SCORE) continue

            val chapters = runCatching {
                source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
            }
                .getOrNull()
                .orEmpty()
            val byNumber = linkedMapOf<Int, SChapter>()
            chapters.forEach { chapter ->
                chapterNumber(chapter)?.let { number -> byNumber.putIfAbsent(number, chapter) }
            }
            if (byNumber.isEmpty()) continue

            return MangaSourceCoverage(
                sourceId = source.id,
                sourceName = source.name,
                sourceManga = manga,
                chapters = byNumber,
            )
        }
        return null
    }

    /**
     * Pages d'un chapitre. En cas d'échec de la source principale, on réessaie
     * sur les autres sources activées.
     */
    suspend fun pages(workUrl: String, chapterNumber: Int): ResolvedMangaPages? =
        withContext(Dispatchers.IO) {
            val work = workStore.get(workUrl) ?: return@withContext null
            val primary = coverage(work, workUrl)
            val candidates = mutableListOf<MangaSourceCoverage>()
            primary?.let(candidates::add)
            for (source in enabledSources()) {
                if (source.id == primary?.sourceId) continue
                val other = runCatching {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        resolveCoverage(source, work, searchQueries(work))
                    }
                }.getOrNull()
                if (other != null && chapterNumber in other.chapters) {
                    candidates += other
                }
            }

            for (candidate in candidates) {
                val chapter = candidate.chapters[chapterNumber] ?: continue
                val source = sourceManager.get(candidate.sourceId) as? HttpSource ?: continue
                val pages = runCatching { source.getPageList(chapter) }
                    .onFailure {
                        logcat(LogPriority.WARN, it) {
                            "Multi-source manga : pages impossibles sur ${candidate.sourceName}"
                        }
                    }
                    .getOrNull()
                    .orEmpty()
                if (pages.isNotEmpty()) {
                    return@withContext ResolvedMangaPages(source, pages)
                }
            }
            null
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
        return TRAILING_NUMBER.find(chapter.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
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
        const val CACHE_TTL_MS = 30 * 60 * 1000L
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val OTHER_SOURCES_GRACE_MS = 3_000L
        const val PARALLEL_SOURCES = 4
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
    }
}
