package com.aibookkeeper.core.data.local.dao

import androidx.sqlite.db.SupportSQLiteDatabase
import com.aibookkeeper.core.data.local.PrepopulateCallback
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CategoryCatalogTest {
    private val food = CategoryEntity(1, "餐饮", "ic_food", "#FF5722", "EXPENSE")

    @Test
    fun `local defaults remain the exact sixteen cloud seed categories`() {
        val sql = mutableListOf<String>()
        val database = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { database.execSQL(capture(sql)) } just Runs
        PrepopulateCallback().onCreate(database)
        val categoryRows = sql.filter { it.startsWith("INSERT INTO categories ") }
        assertEquals(16, categoryRows.size)
        assertEquals(10, categoryRows.count { it.contains("'EXPENSE'") })
        assertEquals(6, categoryRows.count { it.contains("'INCOME'") })
        assertEquals(
            listOf(
                "餐饮", "交通", "购物", "娱乐", "居住", "医疗", "教育", "通讯", "服饰", "其他",
                "工资", "奖金", "兼职", "理财", "红包", "其他"
            ),
            categoryRows.map { Regex("VALUES \\(\\d+, '([^']+)'").find(it)!!.groupValues[1] }
        )
    }

    @Test
    fun `cloud metadata replaces local metadata without replacing IDs or hierarchy`() = runTest {
        val custom = food.copy(id = 27, name = "宠物 食品", parentId = 1, isSystem = false)
        val dao = MemoryCategoryDao(listOf(food, custom))
        val cloud = custom.copy(
            id = 900,
            name = "  宠物   食品  ",
            icon = "🐈",
            color = "#123ABC",
            sortOrder = 70,
            parentId = null
        )

        dao.mergeCloudCatalog(listOf(cloud))

        assertEquals(listOf(food, cloud.copy(id = 27, name = "宠物 食品", parentId = 1)), dao.getAllOnce())
    }

    @Test
    fun `new cloud category cannot overwrite colliding local ID or delete absent history`() = runTest {
        val unused = food.copy(id = 40, name = "本地未使用", isSystem = false)
        val dao = MemoryCategoryDao(listOf(food, unused))

        dao.mergeCloudCatalog(listOf(food.copy(id = 1, name = "Web分类", icon = "🪴", isSystem = false)))

        val rows = dao.getAllOnce()
        assertEquals(food, rows.first())
        assertEquals(unused, rows[1])
        assertEquals("Web分类", rows.last().name)
        assertNotEquals(1L, rows.last().id)
        assertFalse(rows.last().isSystem)
    }

    @Test
    fun `all duplicate normalized local names retain transaction reference IDs`() = runTest {
        val dao = MemoryCategoryDao(
            listOf(food.copy(id = 20, name = "Pet food"), food.copy(id = 21, name = " Pet  food "))
        )

        dao.mergeCloudCatalog(listOf(food.copy(id = 800, name = "Pet food", icon = "🐕")))

        assertEquals(listOf(20L, 21L), dao.getAllOnce().map { it.id })
        assertEquals(listOf("🐕", "🐕"), dao.getAllOnce().map { it.icon })
    }

    @Test
    fun `unknown historical category is inserted with payload metadata and reused by name and type`() = runTest {
        val dao = MemoryCategoryDao(listOf(food))

        val expense = dao.resolveRemoteCategory("  咖啡  豆 ", "EXPENSE", "☕", "#AABBCC")
        val repeated = dao.resolveRemoteCategory("咖啡 豆", "EXPENSE", "old", "#000000")
        val income = dao.resolveRemoteCategory("咖啡 豆", "INCOME", null, null)

        assertEquals(expense, repeated)
        assertNotEquals(expense, income)
        assertEquals("☕", dao.getById(expense)?.icon)
        assertEquals("#AABBCC", dao.getById(expense)?.color)
        assertEquals("ic_other_income", dao.getById(income)?.icon)
    }

    @Test
    fun `historical transaction metadata never overwrites canonical category metadata`() = runTest {
        val dao = MemoryCategoryDao(listOf(food))

        assertEquals(1L, dao.resolveRemoteCategory(" 餐饮 ", "EXPENSE", "old", "#000000"))
        assertEquals(food, dao.getAllOnce().single())
    }

    private class MemoryCategoryDao(initial: List<CategoryEntity>) : CategoryDao {
        private val rows = MutableStateFlow(initial)

        override suspend fun insert(category: CategoryEntity): Long {
            val id = category.id.takeIf { it != 0L } ?: ((rows.value.maxOfOrNull { it.id } ?: 0L) + 1)
            rows.value = rows.value.filterNot { it.id == id } + category.copy(id = id)
            return id
        }

        override suspend fun insertAll(categories: List<CategoryEntity>) {
            categories.forEach { insert(it) }
        }

        override suspend fun update(category: CategoryEntity) {
            rows.value = rows.value.map { if (it.id == category.id) category else it }
        }

        override suspend fun delete(category: CategoryEntity) {
            rows.value = rows.value.filterNot { it.id == category.id }
        }

        override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }
        override fun observeAll(): Flow<List<CategoryEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun count() = rows.value.size
        override suspend fun findByNameAndType(name: String, type: String) =
            rows.value.firstOrNull { it.name == name && it.type == type }
        override fun observeTopLevelByType(type: String) =
            rows.map { items -> items.filter { it.type == type && it.parentId == null } }
        override fun observeSubCategories(parentId: Long) =
            rows.map { items -> items.filter { it.parentId == parentId } }
    }
}
