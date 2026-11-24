package eu.kanade.tachiyomi.data.multisource

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Chapter mapping of a multi-source manga, built the way the anime one is.
 *
 * Every source that has the work contributes its chapters. Each chapter number is assigned to a
 * primary source, with up to [MAX_ALTERNATIVES] fallbacks, and the result is saved so the chapter
 * list and the pages never have to search the sources again.
 */
class MultiSourceMangaMapper(
    private val workStore: MultiSourceWorkStore = Injekt.get(),
    private val mappingStore: MultiSourceMappingStore = Injekt.get(),
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val resolver: CatalogMangaSourceResolver = CatalogMangaSourceResolver(),
) {

    data class Mapping(
        val total: Int,
        val assigned: Int,
        val complete: Boolean,
        val sources: List<PersistedSourceCompatibility>,
    )

    // One build per work at a time: the page, the list and the library update may ask together.
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The saved mapping while it is still fresh, otherwise a new one. */
    suspend fun ensure(workUrl: String, force: Boolean = false): Mapping? {
        val lock = locks.getOrPut(workUrl) { Mutex() }
        return lock.withLock {
            if (!force) cached(workUrl)?.let { return@withLock it }
            build(workUrl)
        }
    }

    /** The saved mapping if it can still be trusted, without any network. */
    fun cached(workUrl: String): Mapping? {
        val report = mappingStore.getReport(workUrl) ?: return null
        val selection = mappingStore.getSelectedMangaSourceIds()
        val sameSelection = report.selectedSourceIds?.toSet() == selection
        val age = System.currentTimeMillis() - report.createdAt
        val fresh = when {
            report.version < MultiSourceMappingReport.CURRENT_VERSION -> false
            !sameSelection -> false
            // Nothing found: do not search again for a few minutes.
            report.assignedEpisodes == 0 -> age < NOTHING_FOUND_TTL_MS
            // A partial mapping may only be missing a slow source: rebuilt after a while.
            else -> report.complete || age < INCOMPLETE_TTL_MS
        }
        if (!fresh) return null
        if (report.assignedEpisodes > 0 && mappingStore.get(workUrl).isEmpty()) return null
        return Mapping(report.totalEpisodes, report.assignedEpisodes, report.complete, report.sources)
    }

    /** Primary and fallback sources of a chapter, best first. */
    fun refsFor(workUrl: String, chapterNumber: Int): List<MultiSourceEpisodeRef> {
        val primary = mappingStore.get(workUrl)[chapterNumber] ?: return emptyList()
        val alternatives = mappingStore.getAlternatives(workUrl)[chapterNumber].orEmpty()
        return (listOf(primary) + alternatives).distinctBy { it.sourceId to it.episodeUrl }
    }

    /** One entry per chapter number, in reading order. */
    fun chapters(workUrl: String): List<MultiSourceEpisodeRef> =
        mappingStore.get(workUrl).entries.sortedBy { it.key }.map { it.value }

    /** Forgets the mapping so the next use rebuilds it (a source was chosen, or a retry). */
    fun invalidate(workUrl: String) {
        mappingStore.clear(workUrl)
    }

    /** Pages of a chapter: the primary source first, then its fallbacks. */
    suspend fun pages(workUrl: String, chapterNumber: Int): ResolvedMangaPages? {
        ensure(workUrl)
        for (ref in refsFor(workUrl, chapterNumber)) {
            val source = sourceManager.get(ref.sourceId) as? HttpSource ?: continue
            val pages: List<Page> = runCatching {
                withTimeoutOrNull(PAGES_TIMEOUT_MS) { source.getPageList(ref.toChapter()) }
            }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Multi-source manga : pages impossibles sur ${ref.sourceName}"
                    }
                }
                .getOrNull()
                .orEmpty()
            if (pages.isNotEmpty()) return ResolvedMangaPages(source, pages)
        }
        return null
    }

    private suspend fun build(workUrl: String): Mapping? {
        val work = workStore.get(workUrl) ?: return null
        val selection = mappingStore.getSelectedMangaSourceIds()
        val preferred = workStore.preferredSource(workUrl)
        val previous = mappingStore.get(workUrl)
        val previousReport = mappingStore.getReport(workUrl)

        // Sources that worked for this work before are searched first.
        val priority = buildSet {
            preferred?.let(::add)
            previousReport?.sources.orEmpty().forEach { add(it.sourceId) }
            previous.values.forEach { add(it.sourceId) }
        }

        val coverages = resolver.coverages(work, selection, priority)
            .sortedWith(
                compareByDescending<MangaSourceCoverage> { it.sourceId == preferred }
                    .thenByDescending { it.chapters.size }
                    .thenBy { it.sourceName.lowercase() },
            )

        // A search that found nothing (network, slow sources) must not erase a working mapping.
        if (coverages.isEmpty() && previous.isNotEmpty() && previousReport != null) {
            return Mapping(
                previousReport.totalEpisodes,
                previousReport.assignedEpisodes,
                previousReport.complete,
                previousReport.sources,
            )
        }

        val (assignments, alternatives) = assignChapters(coverages, MAX_ALTERNATIVES)

        val covered = assignments.size
        val total = maxOf(covered, work.episodes ?: 0)
        val complete = covered > 0 && covered >= total
        val sources = coverages.map { coverage ->
            PersistedSourceCompatibility(
                sourceId = coverage.sourceId,
                sourceName = coverage.sourceName,
                compatibility = if (total > 0) coverage.chapters.size.toFloat() / total else 0f,
                coveredEpisodes = coverage.chapters.size,
                assignedEpisodes = assignments.values.count { it.sourceId == coverage.sourceId },
            )
        }

        mappingStore.save(workUrl, assignments)
        mappingStore.saveAlternatives(workUrl, alternatives)
        mappingStore.saveReport(
            workUrl,
            MultiSourceMappingReport(
                totalEpisodes = total,
                assignedEpisodes = covered,
                complete = complete,
                sources = sources,
                version = MultiSourceMappingReport.CURRENT_VERSION,
                selectedSourceIds = selection?.sorted(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return Mapping(total, covered, complete, sources)
    }

    private fun MultiSourceEpisodeRef.toChapter(): SChapter = SChapter.create().also { chapter ->
        chapter.url = episodeUrl
        chapter.name = episodeName
        chapter.chapter_number = episodeNumber.toFloat()
        chapter.date_upload = dateUpload
        chapter.scanlator = scanlator
        // Sources written for extensions-lib 1.6 may need their own metadata back.
        memo?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()?.let { chapter.memo = it }
        }
    }

    private companion object {
        const val MAX_ALTERNATIVES = 3
        const val PAGES_TIMEOUT_MS = 20_000L
        const val INCOMPLETE_TTL_MS = 15 * 60_000L
        const val NOTHING_FOUND_TTL_MS = 5 * 60_000L
    }
}

/** Primary source and fallbacks of every chapter number. */
internal data class ChapterAssignment(
    val primary: java.util.SortedMap<Int, MultiSourceEpisodeRef>,
    val alternatives: Map<Int, List<MultiSourceEpisodeRef>>,
)

/**
 * Gives each chapter number to the first source that has it; the sources after it, up to
 * [maxAlternatives], become its fallbacks. [coverages] must already be in priority order.
 */
internal fun assignChapters(coverages: List<MangaSourceCoverage>, maxAlternatives: Int): ChapterAssignment {
    val primary = sortedMapOf<Int, MultiSourceEpisodeRef>()
    val alternatives = mutableMapOf<Int, MutableList<MultiSourceEpisodeRef>>()
    coverages.forEach { coverage ->
        coverage.chapters.forEach { (number, chapter) ->
            val ref = coverage.refFor(number, chapter)
            if (number !in primary) {
                primary[number] = ref
            } else {
                val list = alternatives.getOrPut(number) { mutableListOf() }
                if (list.size < maxAlternatives) list += ref
            }
        }
    }
    return ChapterAssignment(primary, alternatives)
}

private fun MangaSourceCoverage.refFor(number: Int, chapter: SChapter) = MultiSourceEpisodeRef(
    sourceId = sourceId,
    sourceName = sourceName,
    animeUrl = sourceManga.url,
    animeTitle = sourceManga.title,
    episodeUrl = chapter.url,
    episodeNumber = number,
    episodeName = chapter.name,
    dateUpload = chapter.date_upload,
    scanlator = chapter.scanlator,
    memo = chapter.memo.takeIf { it.isNotEmpty() }?.toString(),
)
