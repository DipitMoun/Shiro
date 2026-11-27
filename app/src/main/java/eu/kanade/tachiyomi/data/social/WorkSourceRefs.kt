package eu.kanade.tachiyomi.data.social

import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Builds the [WorkSourceRef] of a library work. Null when the work does not come from an
 * installed extension (multi-source entries, local files, Telegram): there is nothing a friend
 * could install to get the same version.
 */
object WorkSourceRefs {

    fun forAnime(anime: Anime): WorkSourceRef? {
        if (anime.url.isBlank()) return null
        val extension = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
            .firstOrNull { ext -> ext.sources.any { it.id == anime.source } } ?: return null
        val source = extension.sources.first { it.id == anime.source }
        return WorkSourceRef(WorkSourceRef.ANIME, anime.source, source.name, extension.pkgName, anime.url, anime.title)
    }

    fun forManga(manga: Manga): WorkSourceRef? {
        if (manga.url.isBlank()) return null
        val extension = Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value
            .firstOrNull { ext -> ext.sources.any { it.id == manga.source } } ?: return null
        val source = extension.sources.first { it.id == manga.source }
        return WorkSourceRef(WorkSourceRef.MANGA, manga.source, source.name, extension.pkgName, manga.url, manga.title)
    }
}
