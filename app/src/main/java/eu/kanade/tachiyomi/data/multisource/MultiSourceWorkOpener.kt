package eu.kanade.tachiyomi.data.multisource

import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MultiSourceWorkOpener(
    private val store: MultiSourceWorkStore = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
) {
    suspend fun open(work: CatalogWork): Long {
        val url = store.put(work)
        val sAnime = work.toSAnime(url)
        return networkToLocalAnime.await(sAnime.toDomainAnime(MultiSourceAnimeSource.ID)).id
    }
}
