package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeCategoriesRestorer(
    private val animeHandler: AnimeDatabaseHandler = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    /** @return the database id of each restored backup category. */
    suspend operator fun invoke(backupCategories: List<BackupCategory>): Map<BackupCategory, Long> {
        if (backupCategories.isEmpty()) return emptyMap()

        val dbCategories = getAnimeCategories.await()
        val restored = restoreCategoryTree(backupCategories, dbCategories) { name, order, flags, parentId ->
            animeHandler.awaitOneExecutable {
                categoriesQueries.insert(name, order, flags, parentId)
                categoriesQueries.selectLastInsertedRowId()
            }
        }

        libraryPreferences.categorizedDisplaySettings().set(
            (dbCategories.map { it.flags } + backupCategories.map { it.flags })
                .distinct()
                .size > 1,
        )

        return restored
    }
}
