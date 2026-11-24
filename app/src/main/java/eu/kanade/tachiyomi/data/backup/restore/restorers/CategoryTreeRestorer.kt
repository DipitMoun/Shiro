package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.domain.category.model.Category

/**
 * Restores Shiro's nested category tree.
 *
 * A category is identified by its name *and* its parent: two sub-collections may share a
 * name under different parents. Sort orders are per level, so a new category gets the next
 * order among its siblings.
 *
 * @return the database id of every restored backup category. Keys are compared by identity,
 * which keeps old backups working even when all their category ids are 0.
 */
internal suspend fun restoreCategoryTree(
    backupCategories: List<BackupCategory>,
    dbCategories: List<Category>,
    insert: suspend (name: String, order: Long, flags: Long, parentId: Long?) -> Long,
): Map<BackupCategory, Long> {
    val backupById = backupCategories
        .filter { it.id > 0L }
        .groupBy { it.id }
        .mapValues { it.value.first() }

    val known = dbCategories
        .filterNot(Category::isSystemCategory)
        .map { KnownCategory(it.id, it.name, it.parentId, it.order) }
        .toMutableList()

    val restored = LinkedHashMap<BackupCategory, Long>()

    suspend fun resolve(category: BackupCategory, path: List<BackupCategory>): Long {
        restored[category]?.let { return it }

        // A missing or cyclic parent puts the category back at the root.
        val parent = category.parentId
            ?.let { backupById[it] }
            ?.takeIf { candidate -> candidate !== category && path.none { it === candidate } }
        val parentDbId = parent?.let { resolve(it, path + category) }

        val dbId = known.firstOrNull { it.name == category.name && it.parentId == parentDbId }?.id
            ?: run {
                val order = known.filter { it.parentId == parentDbId }.maxOfOrNull { it.order }?.plus(1) ?: 0L
                insert(category.name, order, category.flags, parentDbId)
                    .also { known += KnownCategory(it, category.name, parentDbId, order) }
            }

        restored[category] = dbId
        return dbId
    }

    backupCategories
        .sortedBy { it.order }
        .forEach { resolve(it, emptyList()) }

    return restored
}

/**
 * Database category ids an entry must be put in.
 *
 * Shiro backups store category ids ([categoryIds]). Older backups only store sort orders,
 * which are ambiguous with nested categories: a root category is preferred, matching what
 * the order meant before nesting existed.
 */
internal fun resolveEntryCategories(
    categoryIds: List<Long>,
    categoryOrders: List<Long>,
    backupCategories: List<BackupCategory>,
    restoredCategories: Map<BackupCategory, Long>,
): List<Long> {
    val backupMatches = if (categoryIds.isNotEmpty()) {
        val byId = backupCategories.filter { it.id > 0L }.associateBy { it.id }
        categoryIds.mapNotNull { byId[it] }
    } else {
        val byOrder = backupCategories.groupBy { it.order }
        categoryOrders.mapNotNull { order ->
            val candidates = byOrder[order].orEmpty()
            candidates.singleOrNull()
                ?: candidates.firstOrNull { it.parentId == null }
                ?: candidates.firstOrNull()
        }
    }

    return backupMatches
        .mapNotNull { restoredCategories[it] }
        .distinct()
}

private data class KnownCategory(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val order: Long,
)
