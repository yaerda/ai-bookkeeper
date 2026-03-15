package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.mapper.CategoryMapper
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CategoryRepositoryImplTest {

    private lateinit var categoryDao: CategoryDao
    private lateinit var mapper: CategoryMapper
    private lateinit var repository: CategoryRepositoryImpl

    private val sampleEntity = CategoryEntity(
        id = 1, name = "餐饮", icon = "ic_food", color = "#FF5722", type = "EXPENSE"
    )

    private val sampleDomain = Category(
        id = 1, name = "餐饮", icon = "ic_food", color = "#FF5722",
        type = TransactionType.EXPENSE
    )

    @BeforeEach
    fun setUp() {
        categoryDao = mockk(relaxUnitFun = true)
        mapper = mockk()
        repository = CategoryRepositoryImpl(categoryDao, mapper)
    }

    // ── observeAllCategories ─────────────────────────────────────────────

    @Nested
    inner class ObserveAll {

        @Test
        fun should_returnMappedCategories_when_observeAll() = runTest {
            every { categoryDao.observeAll() } returns flowOf(listOf(sampleEntity))
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val result = repository.observeAllCategories().first()

            assertEquals(1, result.size)
            assertEquals("餐饮", result[0].name)
        }

        @Test
        fun should_returnEmptyList_when_noCategories() = runTest {
            every { categoryDao.observeAll() } returns flowOf(emptyList())

            val result = repository.observeAllCategories().first()

            assertTrue(result.isEmpty())
        }
    }

    // ── observeExpenseCategories ──────────────────────────────────────────

    @Nested
    inner class ObserveExpense {

        @Test
        fun should_filterExpenseType_when_observeExpenseCategories() = runTest {
            every {
                categoryDao.observeTopLevelByType("EXPENSE")
            } returns flowOf(listOf(sampleEntity))
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val result = repository.observeExpenseCategories().first()

            assertEquals(1, result.size)
            assertEquals(TransactionType.EXPENSE, result[0].type)
        }
    }

    // ── observeIncomeCategories ──────────────────────────────────────────

    @Nested
    inner class ObserveIncome {

        @Test
        fun should_filterIncomeType_when_observeIncomeCategories() = runTest {
            val incomeEntity = sampleEntity.copy(id = 10, name = "工资", type = "INCOME")
            val incomeDomain = sampleDomain.copy(id = 10, name = "工资", type = TransactionType.INCOME)

            every {
                categoryDao.observeTopLevelByType("INCOME")
            } returns flowOf(listOf(incomeEntity))
            every { mapper.toDomain(incomeEntity) } returns incomeDomain

            val result = repository.observeIncomeCategories().first()

            assertEquals(1, result.size)
            assertEquals(TransactionType.INCOME, result[0].type)
        }
    }

    // ── observeSubCategories ─────────────────────────────────────────────

    @Nested
    inner class ObserveSubCategories {

        @Test
        fun should_returnSubCategories_when_parentIdProvided() = runTest {
            val subEntity = sampleEntity.copy(id = 20, parentId = 1)
            val subDomain = sampleDomain.copy(id = 20, parentId = 1)

            every { categoryDao.observeSubCategories(1L) } returns flowOf(listOf(subEntity))
            every { mapper.toDomain(subEntity) } returns subDomain

            val result = repository.observeSubCategories(1L).first()

            assertEquals(1, result.size)
            assertEquals(1L, result[0].parentId)
        }
    }

    // ── getById ──────────────────────────────────────────────────────────

    @Nested
    inner class GetById {

        @Test
        fun should_returnCategory_when_found() = runTest {
            coEvery { categoryDao.getById(1L) } returns sampleEntity
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val result = repository.getById(1L)

            assertNotNull(result)
            assertEquals("餐饮", result?.name)
        }

        @Test
        fun should_returnNull_when_notFound() = runTest {
            coEvery { categoryDao.getById(999L) } returns null

            val result = repository.getById(999L)

            assertNull(result)
        }
    }

    // ── findByNameAndType ────────────────────────────────────────────────

    @Nested
    inner class FindByNameAndType {

        @Test
        fun should_returnCategory_when_matchFound() = runTest {
            coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } returns sampleEntity
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val result = repository.findByNameAndType("餐饮", TransactionType.EXPENSE)

            assertNotNull(result)
            assertEquals("餐饮", result?.name)
            assertEquals(TransactionType.EXPENSE, result?.type)
        }

        @Test
        fun should_returnNull_when_noMatch() = runTest {
            coEvery { categoryDao.findByNameAndType("不存在", "EXPENSE") } returns null

            val result = repository.findByNameAndType("不存在", TransactionType.EXPENSE)

            assertNull(result)
        }
    }

    // ── create ───────────────────────────────────────────────────────────

    @Nested
    inner class Create {

        @Test
        fun should_returnInsertedId_when_createSucceeds() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { categoryDao.insert(sampleEntity) } returns 10L

            val result = repository.create(sampleDomain)

            assertTrue(result.isSuccess)
            assertEquals(10L, result.getOrThrow())
        }

        @Test
        fun should_returnFailure_when_insertThrows() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { categoryDao.insert(sampleEntity) } throws RuntimeException("DB error")

            val result = repository.create(sampleDomain)

            assertTrue(result.isFailure)
        }
    }

    // ── update ───────────────────────────────────────────────────────────

    @Nested
    inner class Update {

        @Test
        fun should_returnSuccess_when_updateSucceeds() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { categoryDao.update(sampleEntity) } just Runs

            val result = repository.update(sampleDomain)

            assertTrue(result.isSuccess)
        }

        @Test
        fun should_returnFailure_when_updateThrows() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { categoryDao.update(sampleEntity) } throws RuntimeException("update error")

            val result = repository.update(sampleDomain)

            assertTrue(result.isFailure)
        }
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Nested
    inner class Delete {

        @Test
        fun should_returnSuccess_when_deleteSucceeds() = runTest {
            coEvery { categoryDao.getById(1L) } returns sampleEntity
            coEvery { categoryDao.delete(sampleEntity) } just Runs

            val result = repository.delete(1L)

            assertTrue(result.isSuccess)
            coVerify { categoryDao.delete(sampleEntity) }
        }

        @Test
        fun should_returnFailure_when_categoryNotFound() = runTest {
            coEvery { categoryDao.getById(999L) } returns null

            val result = repository.delete(999L)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        fun should_returnFailure_when_deleteThrows() = runTest {
            coEvery { categoryDao.getById(1L) } returns sampleEntity
            coEvery { categoryDao.delete(sampleEntity) } throws RuntimeException("delete error")

            val result = repository.delete(1L)

            assertTrue(result.isFailure)
        }
    }
}
