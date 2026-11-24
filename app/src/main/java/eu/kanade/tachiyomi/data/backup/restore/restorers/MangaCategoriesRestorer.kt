package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaCategoriesRestorer(
    private val mangaHandler: MangaDatabaseHandler = Injekt.get(),
    private val getMangaCategories: GetMangaCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    /** @return the database id of each restored backup category. */
    suspend operator fun invoke(backupCategories: List<BackupCategory>): Map<BackupCategory, Long> {
        if (backupCategories.isEmpty()) return emptyMap()

        val dbCategories = getMangaCategories.await()
        val restored = restoreCategoryTree(backupCategories, dbCategories) { name, order, flags, parentId ->
            mangaHandler.awaitOneExecutable {
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
