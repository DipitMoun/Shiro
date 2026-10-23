package eu.kanade.tachiyomi.data.collection

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.anime.interactor.CreateAnimeCategoryWithName
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.CreateMangaCategoryWithName
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CollectionBootstrap(
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val createAnimeCategory: CreateAnimeCategoryWithName = Injekt.get(),
    private val getMangaCategories: GetMangaCategories = Injekt.get(),
    private val createMangaCategory: CreateMangaCategoryWithName = Injekt.get(),
) {
    suspend fun ensureFavoritesForAll() {
        runCatching { ensureAnimeFavorites() }
            .onFailure { logcat(LogPriority.WARN, it) { "Anime favorites bootstrap failed" } }
        runCatching { ensureMangaFavorites() }
            .onFailure { logcat(LogPriority.WARN, it) { "Manga favorites bootstrap failed" } }
    }

    private suspend fun ensureAnimeFavorites() {
        val categories = getAnimeCategories.await()
        val roots = categories.filter { it.parentId == null && it.id > 0L }
        for (root in roots) {
            val hasFavorites = categories.any {
                it.parentId == root.id && it.name == FAVORITES_NAME
            }
            if (!hasFavorites) {
                runCatching { createAnimeCategory.await(FAVORITES_NAME, root.id) }
            }
        }
    }

    private suspend fun ensureMangaFavorites() {
        val categories = getMangaCategories.await()
        val roots = categories.filter { it.parentId == null && it.id > 0L }
        for (root in roots) {
            val hasFavorites = categories.any {
                it.parentId == root.id && it.name == FAVORITES_NAME
            }
            if (!hasFavorites) {
                runCatching { createMangaCategory.await(FAVORITES_NAME, root.id) }
            }
        }
    }

    companion object {
        const val FAVORITES_NAME = "⭐ Favoris"
    }
}
