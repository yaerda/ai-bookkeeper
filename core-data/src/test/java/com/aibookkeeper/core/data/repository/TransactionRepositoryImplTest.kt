package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.dao.TransactionDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.local.entity.TransactionEntity
import com.aibookkeeper.core.data.mapper.TransactionMapper
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TransactionRepositoryImplTest {

    private lateinit var transactionDao: TransactionDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var mapper: TransactionMapper
    private lateinit var repository: TransactionRepositoryImpl

    private val now = LocalDateTime.of(2026, 3, 15, 10, 30, 0)

    private val sampleEntity = TransactionEntity(
        id = 1, amount = 35.5, type = "EXPENSE", categoryId = 2,
        merchantName = "星巴克", note = "咖啡", date = 1773745800000,
        createdAt = 1773745800000, updatedAt = 1773745800000,
        source = "TEXT_AI", status = "CONFIRMED", syncStatus = "LOCAL",
        aiConfidence = 0.95f
    )

    private val sampleDomain = Transaction(
        id = 1, amount = 35.5, type = TransactionType.EXPENSE, categoryId = 2,
        merchantName = "星巴克", note = "咖啡", date = now,
        createdAt = now, updatedAt = now,
        source = TransactionSource.TEXT_AI, status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.LOCAL, aiConfidence = 0.95f
    )

    private val sampleCategory = CategoryEntity(
        id = 2, name = "餐饮", icon = "ic_food", color = "#FF5722", type = "EXPENSE"
    )

    @BeforeEach
    fun setUp() {
        transactionDao = mockk(relaxUnitFun = true)
        categoryDao = mockk()
        mapper = mockk()
        repository = TransactionRepositoryImpl(transactionDao, categoryDao, mapper)
    }

    // ── create ───────────────────────────────────────────────────────────

    @Nested
    inner class Create {

        @Test
        fun should_returnInsertedId_when_createSucceeds() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { transactionDao.insert(sampleEntity) } returns 42L

            val result = repository.create(sampleDomain)

            assertTrue(result.isSuccess)
            assertEquals(42L, result.getOrThrow())
        }

        @Test
        fun should_returnFailure_when_insertThrows() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { transactionDao.insert(sampleEntity) } throws RuntimeException("DB error")

            val result = repository.create(sampleDomain)

            assertTrue(result.isFailure)
        }

        @Test
        fun should_mapDomainToEntity_when_creating() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { transactionDao.insert(any()) } returns 1L

            repository.create(sampleDomain)

            verify { mapper.toEntity(sampleDomain) }
            coVerify { transactionDao.insert(sampleEntity) }
        }
    }

    // ── getById ──────────────────────────────────────────────────────────

    @Nested
    inner class GetById {

        @Test
        fun should_returnTransaction_when_found() = runTest {
            coEvery { transactionDao.getById(1L) } returns sampleEntity
            coEvery { categoryDao.getById(2L) } returns sampleCategory
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val result = repository.getById(1L)

            assertNotNull(result)
            assertEquals("餐饮", result?.categoryName)
            assertEquals("ic_food", result?.categoryIcon)
            assertEquals("#FF5722", result?.categoryColor)
        }

        @Test
        fun should_returnNull_when_notFound() = runTest {
            coEvery { transactionDao.getById(999L) } returns null

            val result = repository.getById(999L)

            assertNull(result)
        }

        @Test
        fun should_handleNullCategory_when_categoryIdIsNull() = runTest {
            val entityNoCat = sampleEntity.copy(categoryId = null)
            val domainNoCat = sampleDomain.copy(categoryId = null)
            coEvery { transactionDao.getById(1L) } returns entityNoCat
            every { mapper.toDomain(entityNoCat) } returns domainNoCat

            val result = repository.getById(1L)

            assertNotNull(result)
            assertNull(result?.categoryName)
        }
    }

    // ── observeByMonth ───────────────────────────────────────────────────

    @Nested
    inner class ObserveByMonth {

        @Test
        fun should_returnMappedTransactions_when_observed() = runTest {
            every {
                transactionDao.observeByDateRange(any(), any())
            } returns flowOf(listOf(sampleEntity))
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val result = repository.observeByMonth(java.time.YearMonth.of(2026, 3)).first()

            assertEquals(1, result.size)
            assertEquals(35.5, result[0].amount)
        }

        @Test
        fun should_returnEmptyList_when_noTransactions() = runTest {
            every {
                transactionDao.observeByDateRange(any(), any())
            } returns flowOf(emptyList())

            val result = repository.observeByMonth(java.time.YearMonth.of(2026, 3)).first()

            assertTrue(result.isEmpty())
        }
    }

    // ── update ───────────────────────────────────────────────────────────

    @Nested
    inner class Update {

        @Test
        fun should_returnSuccess_when_updateSucceeds() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { transactionDao.update(sampleEntity) } just Runs

            val result = repository.update(sampleDomain)

            assertTrue(result.isSuccess)
        }

        @Test
        fun should_returnFailure_when_updateThrows() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { transactionDao.update(sampleEntity) } throws RuntimeException("update error")

            val result = repository.update(sampleDomain)

            assertTrue(result.isFailure)
        }
    }

    // ── confirmTransaction ───────────────────────────────────────────────

    @Nested
    inner class ConfirmTransaction {

        @Test
        fun should_updateStatusToConfirmed_when_confirming() = runTest {
            coEvery {
                transactionDao.updateStatus(5L, "CONFIRMED", any())
            } just Runs

            val result = repository.confirmTransaction(5L)

            assertTrue(result.isSuccess)
            coVerify { transactionDao.updateStatus(5L, "CONFIRMED", any()) }
        }
    }

    // ── confirmAll ───────────────────────────────────────────────────────

    @Nested
    inner class ConfirmAll {

        @Test
        fun should_confirmMultiple_when_multipleIds() = runTest {
            coEvery { transactionDao.updateStatus(any(), "CONFIRMED", any()) } just Runs

            val result = repository.confirmAll(listOf(1L, 2L, 3L))

            assertTrue(result.isSuccess)
            coVerify(exactly = 3) { transactionDao.updateStatus(any(), "CONFIRMED", any()) }
        }

        @Test
        fun should_handleEmptyList_when_noIds() = runTest {
            val result = repository.confirmAll(emptyList())

            assertTrue(result.isSuccess)
            coVerify(exactly = 0) { transactionDao.updateStatus(any(), any(), any()) }
        }
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Nested
    inner class Delete {

        @Test
        fun should_callDaoDelete_when_deleting() = runTest {
            coEvery { transactionDao.deleteById(42L) } just Runs

            val result = repository.delete(42L)

            assertTrue(result.isSuccess)
            coVerify { transactionDao.deleteById(42L) }
        }

        @Test
        fun should_returnFailure_when_deleteThrows() = runTest {
            coEvery { transactionDao.deleteById(42L) } throws RuntimeException("delete error")

            val result = repository.delete(42L)

            assertTrue(result.isFailure)
        }
    }

    // ── search ───────────────────────────────────────────────────────────

    @Nested
    inner class Search {

        @Test
        fun should_returnMappedResults_when_searchFindsMatches() = runTest {
            coEvery { transactionDao.search("星巴克") } returns listOf(sampleEntity)
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val results = repository.search("星巴克")

            assertEquals(1, results.size)
            assertEquals("星巴克", results[0].merchantName)
        }

        @Test
        fun should_returnEmptyList_when_searchFindsNothing() = runTest {
            coEvery { transactionDao.search("不存在") } returns emptyList()

            val results = repository.search("不存在")

            assertTrue(results.isEmpty())
        }
    }

    // ── sync ─────────────────────────────────────────────────────────────

    @Nested
    inner class Sync {

        @Test
        fun should_returnPendingSyncTransactions_when_called() = runTest {
            coEvery { transactionDao.getPendingSyncTransactions() } returns listOf(sampleEntity)
            every { mapper.toDomain(sampleEntity) } returns sampleDomain

            val results = repository.getPendingSync()

            assertEquals(1, results.size)
        }

        @Test
        fun should_updateSyncStatus_when_markSynced() = runTest {
            coEvery { transactionDao.updateSyncStatus(any(), "SYNCED") } just Runs

            repository.markSynced(listOf(1L, 2L))

            coVerify { transactionDao.updateSyncStatus(1L, "SYNCED") }
            coVerify { transactionDao.updateSyncStatus(2L, "SYNCED") }
        }
    }
}
