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

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getAnimeCategories.await()
            val dbCategoriesByName = dbCategories.associateBy { it.name }
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0
            
            // Correctif Shiro : map pour faire correspondre l'ancien ID du backup au nouvel ID
            val idMap = mutableMapOf<Long, Long>()

            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    val dbCategory = dbCategoriesByName[it.name]
                    if (dbCategory != null) {
                        idMap[it.id] = dbCategory.id
                        return@map dbCategory
                    }
                    val order = nextOrder++
                    val newId = animeHandler.awaitOneExecutable {
                        categoriesQueries.insert(it.name, order, it.flags, null)
                        categoriesQueries.selectLastInsertedRowId()
                    }
                    idMap[it.id] = newId
                    it.toCategory(newId).copy(order = order)
                }

            // Correctif Shiro : 2eme passe pour restaurer l'arbre des parents
            backupCategories.forEach { backupCategory ->
                val newId = idMap[backupCategory.id] ?: return@forEach
                val newParentId = backupCategory.parentId?.let { idMap[it] }
                if (newParentId != null) {
                    animeHandler.await {
                        categoriesQueries.updateParent(newParentId, newId)
                    }
                }
            }

            libraryPreferences.categorizedDisplaySettings().set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}