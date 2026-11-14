package eu.kanade.tachiyomi.data.multisource

import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Ouvre une œuvre du catalogue comme manga multi-source. */
class MultiSourceMangaWorkOpener(
    private val store: MultiSourceWorkStore = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) {

    suspend fun open(work: CatalogWork): Long {
        // Les identifiants MAL/Kitsu ne sont pas forcément uniques entre un
        // animé et un manga : on espace la clé de stockage.
        val stored = work.copy(id = "manga:${work.id}")
        val url = store.put(stored)
        val sManga = stored.toSManga(url)
        return networkToLocalManga.await(sManga.toDomainManga(MultiSourceMangaSource.ID)).id
    }
}
