package eu.kanade.presentation.library.components

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.ui.browse.catalog.AiringEvent
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogRepository
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogType
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import kotlinx.coroutines.flow.first
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

private val NEW_WINDOW_MS = TimeUnit.HOURS.toMillis(48)

private fun numberLabel(prefix: String, number: Double): String =
    if (number <= 0.0) prefix else "$prefix ${if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()}"

/** The next unseen episode of an anime: its number, whether it just arrived, whether it is on the phone. */
suspend fun loadNextAnimeBadge(
    item: AnimeLibraryItem,
    getEpisodes: GetEpisodesByAnimeId,
    downloads: AnimeDownloadManager,
): NextEntryBadge? {
    val anime = item.libraryAnime.anime
    val next = getEpisodes.await(anime.id)
        .filter { !it.seen }
        .minByOrNull { if (it.episodeNumber > 0.0) it.episodeNumber else Double.MAX_VALUE }
        ?: return null
    return NextEntryBadge(
        label = numberLabel("Ép.", next.episodeNumber),
        isNew = next.dateFetch > 0L && System.currentTimeMillis() - next.dateFetch < NEW_WINDOW_MS,
        downloaded = downloads.isEpisodeDownloaded(next.name, next.scanlator, anime.title, anime.source),
    )
}

/** Same for the next unread chapter of a manga. */
suspend fun loadNextMangaBadge(
    item: MangaLibraryItem,
    getChapters: GetChaptersByMangaId,
    downloads: MangaDownloadManager,
): NextEntryBadge? {
    val manga = item.libraryManga.manga
    val next = getChapters.await(manga.id)
        .filter { !it.read }
        .minByOrNull { if (it.chapterNumber > 0.0) it.chapterNumber else Double.MAX_VALUE }
        ?: return null
    return NextEntryBadge(
        label = numberLabel("Ch.", next.chapterNumber),
        isNew = next.dateFetch > 0L && System.currentTimeMillis() - next.dateFetch < NEW_WINDOW_MS,
        downloaded = downloads.isChapterDownloaded(next.name, next.scanlator, manga.title, manga.source),
    )
}

/**
 * Episodes of the user's library that come out in the next days, nearest first, at most one per
 * anime. Reads the AniList schedule (cached, with a fallback on the last answer) for the works
 * AniList knows, and what the work page already stored for the others.
 */
class AnimeUpcomingLoader(
    private val catalog: CatalogRepository = CatalogRepository(Injekt.get(), CatalogType.ANIME),
    private val tracks: AnimeTrackRepository = Injekt.get(),
) {

    suspend fun load(library: List<Anime>, withinDays: Int = 7, limit: Int = 8): List<Pair<Anime, AiringEvent>> {
        if (library.isEmpty()) return emptyList()
        val nowSeconds = System.currentTimeMillis() / 1000L
        val until = nowSeconds + withinDays * 24L * 3600L

        val ids = resolveAniListIds(library)
        val animeByRemoteId = ids.entries.associate { (animeId, remoteId) -> remoteId to library.first { it.id == animeId } }
        val scheduled = if (animeByRemoteId.isNotEmpty()) catalog.getAiringSchedule(animeByRemoteId.keys.toList()) else emptyList()

        val events = scheduled.mapNotNull { event ->
            animeByRemoteId[event.mediaId]?.let { it to event }
        }.toMutableList()

        // The work page keeps the next airing of the works it has opened.
        library.forEach { anime ->
            if (events.any { it.first.id == anime.id }) return@forEach
            val at = anime.nextEpisodeAiringAt
            if (at <= 0L) return@forEach
            events += anime to AiringEvent(
                mediaId = ids[anime.id] ?: 0,
                episode = anime.nextEpisodeToAir,
                airingAt = at,
                title = anime.title,
                coverUrl = anime.thumbnailUrl,
                animeId = anime.id,
            )
        }

        return events
            .filter { (_, event) -> event.airingAt in nowSeconds..until && event.episode > 0 }
            .sortedBy { it.second.airingAt }
            .distinctBy { it.first.id }
            .take(limit)
    }

    /** AniList id of one anime: its tracker entry first, then a title lookup. */
    suspend fun aniListIdFor(anime: Anime): Int? {
        val tracked = runCatching { tracks.getAnimeTracksAsFlow().first() }.getOrDefault(emptyList())
            .filter { it.animeId == anime.id }
        catalogAniListId(anime)?.let { return it }
        tracked.firstOrNull { it.trackerId == ANILIST_TRACKER_ID }?.let { return it.remoteId.toInt() }
        tracked.firstOrNull { it.trackerId == MAL_TRACKER_ID }
            ?.let { catalog.resolveAniListIdByMal(it.remoteId) }?.let { return it }
        return catalog.resolveAniListIdByTitle(anime.title)
    }

    /** AniList id kept in the catalogue entry of a multi-source work (the common case). */
    private suspend fun catalogAniListId(anime: Anime): Int? {
        if (anime.source != eu.kanade.tachiyomi.data.multisource.MultiSourceAnimeSource.ID) return null
        val id = runCatching { Injekt.get<eu.kanade.tachiyomi.data.multisource.MultiSourceWorkStore>().get(anime.url)?.id }
            .getOrNull() ?: return null
        return when {
            id.startsWith("anilist:") -> id.removePrefix("anilist:").toIntOrNull()
            id.startsWith("mal:") -> id.removePrefix("mal:").toLongOrNull()?.let { catalog.resolveAniListIdByMal(it) }
            else -> null
        }
    }

    private suspend fun resolveAniListIds(library: List<Anime>): Map<Long, Int> {
        val result = mutableMapOf<Long, Int>()
        library.forEach { anime -> catalogAniListId(anime)?.let { result[anime.id] = it } }
        val libraryIds = library.map { it.id }.toSet()
        val allTracks = runCatching { tracks.getAnimeTracksAsFlow().first() }.getOrDefault(emptyList())
            .filter { it.animeId in libraryIds }

        allTracks.filter { it.trackerId == ANILIST_TRACKER_ID }.forEach { result[it.animeId] = it.remoteId.toInt() }
        allTracks.filter { it.trackerId == MAL_TRACKER_ID && it.animeId !in result }.forEach { track ->
            catalog.resolveAniListIdByMal(track.remoteId)?.let { result[track.animeId] = it }
        }

        // Ongoing works with nothing known yet are looked up by title (a few, newest first).
        // Works whose status the source does not give (0) are looked up too: that is most of them.
        val ongoing = setOf(
            SAnime.ONGOING.toLong(),
            SAnime.PUBLISHING_FINISHED.toLong(),
            SAnime.UPCOMING.toLong(),
            SAnime.UNKNOWN.toLong(),
        )
        library
            .filter { it.id !in result && it.status in ongoing }
            // Works just added come first: they are what the user is waiting to see on the cards.
            .sortedWith(compareByDescending<Anime> { it.dateAdded }.thenByDescending { it.lastUpdate })
            .take(MAX_TITLE_RESOLUTIONS)
            .forEach { anime ->
                catalog.resolveAniListIdByTitle(anime.title)?.let { result[anime.id] = it }
            }
        return result
    }

    private companion object {
        const val ANILIST_TRACKER_ID = 2L
        const val MAL_TRACKER_ID = 1L
        const val MAX_TITLE_RESOLUTIONS = 25
    }
}
