package eu.kanade.presentation.social

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.multisource.MultiSourceMangaWorkOpener
import eu.kanade.tachiyomi.data.multisource.MultiSourceWorkOpener
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogRepository
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogType
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.data.social.identity.EpisodeKey
import eu.kanade.tachiyomi.data.social.identity.WorkId
import eu.kanade.tachiyomi.ui.social.SocialChatContext
import eu.kanade.tachiyomi.ui.social.SocialChatEpisode
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.entries.manga.interactor.GetMangaFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Title helpers shared by the friend profile and the activity feed. */
internal object SocialWorkMatching {

    /** Work title of a room named "Title — Author", or null for a generic room. */
    fun extractTitle(roomName: String): String? {
        val sep = " — "
        val idx = roomName.indexOf(sep)
        if (idx == -1) {
            if (roomName.startsWith("Shiro", ignoreCase = true)) return null
            return roomName.trim().takeIf { it.isNotBlank() }
        }
        val title = roomName.substring(0, idx).trim()
        if (title.equals("Shiro", ignoreCase = true)) return null
        return title.takeIf { it.isNotBlank() }
    }

    fun <T> matchTitle(title: String, items: List<T>, titleOf: (T) -> String): T? {
        val normRoom = normalize(title)
        if (normRoom.isEmpty()) return null
        items.find {
            val normLocal = normalize(titleOf(it))
            normLocal.isNotEmpty() &&
                (normLocal == normRoom || normLocal.contains(normRoom) || normRoom.contains(normLocal))
        }?.let { return it }

        val roomWords = words(title)
        if (roomWords.isEmpty()) return null
        return items.find { item ->
            val localWords = words(titleOf(item))
            roomWords.any { rw -> localWords.any { lw -> lw == rw || lw.contains(rw) || rw.contains(lw) } }
        }
    }

    fun cleanTitleForSearch(raw: String): String {
        var s = raw
        s = s.replace(
            Regex(
                """\s*[\(\[]\s*(vostfr|vf|vo|multi|1080p|720p|480p|hd|sd|web-?dl|bluray|bd)[^\)\]]*[\)\]]""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(Regex("""\s+(saison|season|part|partie)\s*\d+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s+s\d+(?:\s|$)""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""\s+ep?\s*\d+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s+(vostfr|vf)\s*$""", RegexOption.IGNORE_CASE), "")
        return s.trim().replace(Regex("""\s{2,}"""), " ")
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun words(value: String) = value.lowercase().split(Regex("\\s+"))
        .map { it.replace(Regex("[^a-z0-9]"), "") }
        .filter { it.length >= 3 }
}

/**
 * Completes a chat context opened from a room (Messages tab, notification) where only the
 * work id and title are known: finds the local anime and loads its episodes so the chat
 * behaves like the one opened from the work page.
 */
internal object SocialChatContextResolver {

    suspend fun enrich(
        context: SocialChatContext,
        getAnimeFavorites: GetAnimeFavorites = Injekt.get(),
        getEpisodes: GetEpisodesByAnimeId = Injekt.get(),
    ): SocialChatContext {
        if (context.episodes.isNotEmpty()) return context
        val library = runCatching { getAnimeFavorites.await() }.getOrDefault(emptyList())
        val anime = library.firstOrNull { WorkId.localStable(it.source, it.url) == context.work.id }
            ?: library.firstOrNull { it.id == context.animeId }
            ?: SocialWorkMatching.matchTitle(context.title, library) { it.title }
        val episodes = anime?.let { local ->
            runCatching { getEpisodes.await(local.id) }.getOrDefault(emptyList())
                .map { episode ->
                    val number = episode.episodeNumber.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    SocialChatEpisode(
                        id = episode.id,
                        key = EpisodeKey.of(number),
                        number = number,
                        title = episode.name,
                        durationSeconds = episode.totalSeconds,
                    )
                }
                .distinctBy { it.key.value }
        }.orEmpty()
        val resolved = episodes.ifEmpty {
            // Work not in the library: keep the room usable for plain text.
            if (context.initialRoomId == null) return context
            listOf(SocialChatEpisode(id = 0L, key = EpisodeKey.of(0.0), number = 0.0, title = null, durationSeconds = 0L))
        }
        return context.copy(
            animeId = anime?.id ?: context.animeId,
            posterUrl = context.posterUrl ?: anime?.thumbnailUrl,
            episodes = resolved,
        )
    }
}

/**
 * Opens a work known only by its title: from the library when the user has it, otherwise
 * from the catalog (created on the fly as a multi-source entry).
 */
internal class SocialWorkOpener(
    private val getAnimeFavorites: GetAnimeFavorites = Injekt.get(),
    private val getMangaFavorites: GetMangaFavorites = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
) {

    /** @return the screen to push, or null when the work cannot be found. */
    suspend fun open(title: String, isManga: Boolean): Screen? {
        return if (isManga) openManga(title) else openAnime(title)
    }

    private suspend fun openAnime(title: String): Screen? {
        SocialWorkMatching.matchTitle(title, getAnimeFavorites.await()) { it.title }
            ?.let { return AnimeScreen(animeId = it.id, fromSource = false) }
        val work = search(title, CatalogType.ANIME) ?: return null
        return AnimeScreen(animeId = MultiSourceWorkOpener().open(work), fromSource = true)
    }

    private suspend fun openManga(title: String): Screen? {
        SocialWorkMatching.matchTitle(title, getMangaFavorites.await()) { it.title }
            ?.let { return MangaScreen(it.id, false) }
        val work = search(title, CatalogType.MANGA) ?: return null
        return MangaScreen(MultiSourceMangaWorkOpener().open(work), true)
    }

    private suspend fun search(title: String, type: CatalogType) = runCatching {
        val repository = CatalogRepository(network, type)
        val cleaned = SocialWorkMatching.cleanTitleForSearch(title)
        repository.search(cleaned).firstOrNull()
            ?: if (cleaned != title) repository.search(title).firstOrNull() else null
    }.getOrNull()
}
