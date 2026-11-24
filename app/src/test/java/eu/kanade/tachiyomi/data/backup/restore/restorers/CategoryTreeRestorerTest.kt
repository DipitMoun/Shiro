package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCollectionMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupShiroData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class CategoryTreeRestorerTest {

    private class FakeDb(existing: List<Category> = emptyList()) {
        val rows = existing.toMutableList()
        private var nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1

        suspend fun insert(name: String, order: Long, flags: Long, parentId: Long?): Long {
            val id = nextId++
            rows += Category(id, name, order, flags, hidden = false, parentId = parentId)
            return id
        }

        fun byId(id: Long) = rows.first { it.id == id }
    }

    @Test
    fun `same name under different parents creates distinct categories`() = runBlocking {
        val shonen = BackupCategory(name = "Shonen", order = 0, id = 10)
        val seinen = BackupCategory(name = "Seinen", order = 1, id = 11)
        val shonenUnknown = BackupCategory(name = "Non reconnus", order = 0, id = 12, parentId = 10)
        val seinenUnknown = BackupCategory(name = "Non reconnus", order = 0, id = 13, parentId = 11)
        val db = FakeDb()

        val restored = restoreCategoryTree(
            listOf(shonenUnknown, seinenUnknown, shonen, seinen),
            emptyList(),
            db::insert,
        )

        assertEquals(4, db.rows.size)
        assertEquals(restored[shonen], db.byId(restored.getValue(shonenUnknown)).parentId)
        assertEquals(restored[seinen], db.byId(restored.getValue(seinenUnknown)).parentId)
        assertEquals(0L, db.byId(restored.getValue(seinenUnknown)).order)
    }

    @Test
    fun `existing category is reused only under the same parent`() = runBlocking {
        val db = FakeDb(
            listOf(
                Category(1, "Shonen", 0, 0, hidden = false),
                Category(2, "Telegram", 1, 0, hidden = false),
            ),
        )
        val shonen = BackupCategory(name = "Shonen", order = 0, id = 50)
        val telegramChild = BackupCategory(name = "Telegram", order = 0, id = 51, parentId = 50)

        val restored = restoreCategoryTree(listOf(shonen, telegramChild), db.rows.toList(), db::insert)

        assertEquals(1L, restored[shonen])
        val child = db.byId(restored.getValue(telegramChild))
        assertEquals(1L, child.parentId)
        assertEquals(3, db.rows.size)
    }

    @Test
    fun `cyclic or missing parents fall back to root`() = runBlocking {
        val a = BackupCategory(name = "A", order = 0, id = 1, parentId = 2)
        val b = BackupCategory(name = "B", order = 1, id = 2, parentId = 1)
        val orphan = BackupCategory(name = "Orphan", order = 2, id = 3, parentId = 99)
        val db = FakeDb()

        val restored = restoreCategoryTree(listOf(a, b, orphan), emptyList(), db::insert)

        assertEquals(3, restored.size)
        assertNull(db.byId(restored.getValue(orphan)).parentId)
        val roots = listOf(a, b).count { db.byId(restored.getValue(it)).parentId == null }
        assertEquals(1, roots)
    }

    @Test
    fun `legacy backups with zero ids still restore every category`() = runBlocking {
        val first = BackupCategory(name = "One", order = 0)
        val second = BackupCategory(name = "Two", order = 1)
        val db = FakeDb()

        val restored = restoreCategoryTree(listOf(first, second), emptyList(), db::insert)

        assertEquals(2, restored.values.distinct().size)
    }

    @Test
    fun `entry categories resolve by id first`() {
        val root = BackupCategory(name = "Shonen", order = 0, id = 10)
        val child = BackupCategory(name = "Isekai", order = 0, id = 11, parentId = 10)
        val restored = mapOf(root to 100L, child to 101L)

        val byId = resolveEntryCategories(listOf(11), listOf(0), listOf(root, child), restored)
        val legacy = resolveEntryCategories(emptyList(), listOf(0), listOf(root, child), restored)

        assertEquals(listOf(101L), byId)
        assertEquals(listOf(100L), legacy)
    }

    @Test
    fun `shiro fields survive protobuf round trip`() {
        val backup = Backup(
            isLegacy = false,
            backupAnime = listOf(BackupAnime(source = 1, url = "a", categoryIds = listOf(11, 12))),
            shiroData = BackupShiroData(
                collections = listOf(
                    BackupCollectionMetadata(type = "anime", categoryId = 11, bannerBytes = byteArrayOf(1, 2, 3)),
                ),
                telegramWorkOverridesJson = "{}",
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )

        assertEquals(listOf(11L, 12L), decoded.backupAnime.single().categoryIds)
        assertEquals(3, decoded.shiroData!!.collections.single().bannerBytes!!.size)
        assertEquals("{}", decoded.shiroData!!.telegramWorkOverridesJson)
    }
}
