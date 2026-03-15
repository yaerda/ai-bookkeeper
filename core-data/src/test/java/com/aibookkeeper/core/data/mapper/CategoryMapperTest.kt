package com.aibookkeeper.core.data.mapper

import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CategoryMapperTest {

    private lateinit var mapper: CategoryMapper

    @BeforeEach
    fun setUp() {
        mapper = CategoryMapper()
    }

    private fun createEntity(
        id: Long = 1,
        name: String = "餐饮",
        icon: String = "ic_food",
        color: String = "#FF5722",
        type: String = "EXPENSE",
        parentId: Long? = null,
        isSystem: Boolean = true,
        sortOrder: Int = 0
    ) = CategoryEntity(
        id = id, name = name, icon = icon, color = color,
        type = type, parentId = parentId, isSystem = isSystem, sortOrder = sortOrder
    )

    private fun createDomain(
        id: Long = 1,
        name: String = "餐饮",
        icon: String = "ic_food",
        color: String = "#FF5722",
        type: TransactionType = TransactionType.EXPENSE,
        parentId: Long? = null,
        isSystem: Boolean = true,
        sortOrder: Int = 0
    ) = Category(
        id = id, name = name, icon = icon, color = color,
        type = type, parentId = parentId, isSystem = isSystem, sortOrder = sortOrder
    )

    // ── toDomain ─────────────────────────────────────────────────────────

    @Nested
    inner class ToDomain {

        @Test
        fun should_mapAllFields_when_entityHasAllValues() {
            val entity = createEntity()
            val domain = mapper.toDomain(entity)

            assertEquals(1L, domain.id)
            assertEquals("餐饮", domain.name)
            assertEquals("ic_food", domain.icon)
            assertEquals("#FF5722", domain.color)
            assertEquals(TransactionType.EXPENSE, domain.type)
            assertNull(domain.parentId)
            assertTrue(domain.isSystem)
            assertEquals(0, domain.sortOrder)
        }

        @Test
        fun should_mapIncomeType_when_entityTypeIsIncome() {
            val entity = createEntity(type = "INCOME")
            val domain = mapper.toDomain(entity)
            assertEquals(TransactionType.INCOME, domain.type)
        }

        @Test
        fun should_mapParentId_when_entityHasParent() {
            val entity = createEntity(parentId = 5L)
            val domain = mapper.toDomain(entity)
            assertEquals(5L, domain.parentId)
        }

        @Test
        fun should_mapNullParentId_when_entityHasNoParent() {
            val entity = createEntity(parentId = null)
            val domain = mapper.toDomain(entity)
            assertNull(domain.parentId)
        }

        @Test
        fun should_mapNonSystemCategory_when_entityIsNotSystem() {
            val entity = createEntity(isSystem = false)
            val domain = mapper.toDomain(entity)
            assertFalse(domain.isSystem)
        }

        @Test
        fun should_mapSortOrder_when_entityHasCustomOrder() {
            val entity = createEntity(sortOrder = 5)
            val domain = mapper.toDomain(entity)
            assertEquals(5, domain.sortOrder)
        }
    }

    // ── toEntity ─────────────────────────────────────────────────────────

    @Nested
    inner class ToEntity {

        @Test
        fun should_mapAllFields_when_domainHasAllValues() {
            val domain = createDomain()
            val entity = mapper.toEntity(domain)

            assertEquals(1L, entity.id)
            assertEquals("餐饮", entity.name)
            assertEquals("ic_food", entity.icon)
            assertEquals("#FF5722", entity.color)
            assertEquals("EXPENSE", entity.type)
            assertNull(entity.parentId)
            assertTrue(entity.isSystem)
            assertEquals(0, entity.sortOrder)
        }

        @Test
        fun should_mapIncomeType_when_domainTypeIsIncome() {
            val domain = createDomain(type = TransactionType.INCOME)
            val entity = mapper.toEntity(domain)
            assertEquals("INCOME", entity.type)
        }

        @Test
        fun should_mapParentId_when_domainHasParent() {
            val domain = createDomain(parentId = 3L)
            val entity = mapper.toEntity(domain)
            assertEquals(3L, entity.parentId)
        }

        @Test
        fun should_mapNonSystemCategory_when_domainIsNotSystem() {
            val domain = createDomain(isSystem = false)
            val entity = mapper.toEntity(domain)
            assertFalse(entity.isSystem)
        }
    }

    // ── Round-trip ────────────────────────────────────────────────────────

    @Nested
    inner class RoundTrip {

        @Test
        fun should_preserveAllValues_when_domainToEntityAndBack() {
            val original = createDomain(
                id = 3, name = "交通", icon = "ic_transport",
                color = "#2196F3", type = TransactionType.EXPENSE,
                parentId = null, isSystem = true, sortOrder = 2
            )
            val roundTripped = mapper.toDomain(mapper.toEntity(original))

            assertEquals(original.id, roundTripped.id)
            assertEquals(original.name, roundTripped.name)
            assertEquals(original.icon, roundTripped.icon)
            assertEquals(original.color, roundTripped.color)
            assertEquals(original.type, roundTripped.type)
            assertEquals(original.parentId, roundTripped.parentId)
            assertEquals(original.isSystem, roundTripped.isSystem)
            assertEquals(original.sortOrder, roundTripped.sortOrder)
        }

        @Test
        fun should_preserveSubCategory_when_roundTripping() {
            val original = createDomain(parentId = 10L, isSystem = false)
            val roundTripped = mapper.toDomain(mapper.toEntity(original))

            assertEquals(10L, roundTripped.parentId)
            assertFalse(roundTripped.isSystem)
        }
    }
}
