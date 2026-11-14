package eu.kanade.tachiyomi.util

import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.source.local.entries.anime.isLocal
import tachiyomi.source.local.image.anime.LocalAnimeBackgroundManager
import tachiyomi.source.local.image.anime.LocalAnimeCoverManager
import tachiyomi.source.local.image.anime.LocalEpisodeThumbnailManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import eu.kanade.tachiyomi.data.database.models.anime.Episode as SEpisode

fun Anime.removeCovers(coverCache: AnimeCoverCache = Injekt.get()): Anime {
    if (isLocal()) return this
    return if (coverCache.deleteFromCache(this, true) > 0) {
        return copy(coverLastModified = Instant.now().toEpochMilli())
    } else {
        this
    }
}

fun Anime.removeBackgrounds(backgroundCache: AnimeBackgroundCache): Anime {
    if (isLocal()) return this
    return if (backgroundCache.deleteFromCache(this, true) > 0) {
        return copy(backgroundLastModified = Instant.now().toEpochMilli())
    } else {
        this
    }
}

suspend fun Anime.editCover(
    coverManager: LocalAnimeCoverManager,
    stream: InputStream,
    updateAnime: UpdateAnime = Injekt.get(),
    coverCache: AnimeCoverCache = Injekt.get(),
) {
    if (isLocal()) {
        // Same as manga: persist the URI of the freshly created cover file,
        // otherwise the entry still has no thumbnail in the database.
        val cover = coverManager.update(toSAnime(), stream)
            ?: throw IOException("Cannot write cover for local entry $id")
        updateAnime.await(
            AnimeUpdate(
                id = id,
                thumbnailUrl = cover.uri.toString(),
                coverLastModified = Instant.now().toEpochMilli(),
            ),
        )
    } else {
        // Source-only entries (e.g. Telegram works) are not favorites yet but
        // still need a usable custom cover.
        coverCache.setCustomCoverToCache(this, stream)
        updateAnime.awaitUpdateCoverLastModified(id)
    }
}

suspend fun Anime.editBackground(
    backgroundManager: LocalAnimeBackgroundManager,
    stream: InputStream,
    updateAnime: UpdateAnime = Injekt.get(),
    backgroundCache: AnimeBackgroundCache = Injekt.get(),
) {
    if (isLocal()) {
        val background = backgroundManager.update(toSAnime(), stream)
            ?: throw IOException("Cannot write background for local entry $id")
        updateAnime.await(
            AnimeUpdate(
                id = id,
                backgroundUrl = background.uri.toString(),
                backgroundLastModified = Instant.now().toEpochMilli(),
            ),
        )
    } else {
        backgroundCache.setCustomBackgroundToCache(this, stream)
        updateAnime.awaitUpdateBackgroundLastModified(id)
    }
}

fun SEpisode.editThumbnail(
    anime: Anime,
    thumbnailManager: LocalEpisodeThumbnailManager,
    stream: InputStream,
) {
    if (anime.isLocal()) {
        thumbnailManager.update(anime.toSAnime(), this, stream)
    }
}
