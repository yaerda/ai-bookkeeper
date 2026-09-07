package com.aibookkeeper.core.data.repository

import app.cash.turbine.test
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
import kotlinx.coroutines.flow.MutableStateFlow
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
        every { categoryDao.observeAll() } returns flowOf(emptyList())
        mapper = mockk()
        repository = TransactionRepositoryImpl(transactionDao, categoryDao, mapper)
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    fun `batch delegates complete rows and validation to one atomic DAO operation`() = runTest {
        val rows = listOf(sampleDomain.copy(id = 0), sampleDomain.copy(id = 0, syncId = "second"))
        val entities = rows.map { TransactionMapper().toEntity(it) }
        rows.zip(entities).forEach { (row, entity) -> every { mapper.toEntity(row) } returns entity }
        val guard: () -> Unit = {}
        coEvery { transactionDao.insertAllValidated(entities, guard) } returns listOf(1L, 2L)
        assertEquals(listOf(1L, 2L), repository.createAllValidated(rows, guard).getOrThrow())
        coVerify(exactly = 1) { transactionDao.insertAllValidated(entities, guard) }
        coVerify(exactly = 0) { transactionDao.insert(any()) }
    }

    @Test
    fun `pending sync serializes separate intent rather than cached display labels`() = runTest {
        val cached = sampleEntity.copy(
            categoryId = null, projectIdsState = "EXPLICIT", projectIdsBlob = "cached",
            projectIdsWriteState = "UNSPECIFIED", projectIdsWriteBlob = null
        )
        val optedOut = cached.copy(
            id = 2, projectIdsWriteState = "EXPLICIT", projectIdsWriteBlob = ""
        )
        val explicit = cached.copy(
            id = 3, projectIdsWriteState = "EXPLICIT", projectIdsWriteBlob = "picked"
        )
        val mapper = TransactionMapper()
        val repository = TransactionRepositoryImpl(transactionDao, categoryDao, mapper)
        coEvery { transactionDao.getPendingSyncTransactions() } returns listOf(cached, optedOut, explicit)
        val pending = repository.getPendingSync()
        assertNull(pending[0].projectIds)
        assertEquals(emptyList<String>(), pending[1].projectIds)
        assertEquals(listOf("picked"), pending[2].projectIds)
        assertEquals(listOf("cached"), mapper.toDomain(cached).projectIds)
    }

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
            coEvery { categoryDao.getById(2L) } returns sampleCategory

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

        @Test
        fun should_enrichCategoryInfo_when_observingByMonth() = runTest {
            every {
                transactionDao.observeByDateRange(any(), any())
            } returns flowOf(listOf(sampleEntity))
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val result = repository.observeByMonth(java.time.YearMonth.of(2026, 3)).first()

            assertEquals(1, result.size)
            assertEquals("餐饮", result[0].categoryName)
            assertEquals("ic_food", result[0].categoryIcon)
            assertEquals("#FF5722", result[0].categoryColor)
        }

        @Test
        fun should_returnNullCategoryName_when_categoryIdIsNull() = runTest {
            val entityNoCat = sampleEntity.copy(categoryId = null)
            val domainNoCat = sampleDomain.copy(categoryId = null)
            every {
                transactionDao.observeByDateRange(any(), any())
            } returns flowOf(listOf(entityNoCat))
            every { mapper.toDomain(entityNoCat) } returns domainNoCat

            val result = repository.observeByMonth(java.time.YearMonth.of(2026, 3)).first()

            assertEquals(1, result.size)
            assertNull(result[0].categoryName)
        }
    }

    // ── observeById ──────────────────────────────────────────────────────

    @Nested
    inner class ObserveById {

        @Test
        fun `catalog-only metadata update refreshes existing transaction without changing transaction row`() = runTest {
            val categories = MutableStateFlow(listOf(sampleCategory))
            every { categoryDao.observeAll() } returns categories
            every { transactionDao.observeById(1L) } returns flowOf(sampleEntity)
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } answers { categories.value.single() }

            repository.observeById(1).test {
                assertEquals("ic_food", awaitItem()?.categoryIcon)
                categories.value = listOf(sampleCategory.copy(icon = "🍲", color = "#123ABC"))
                val updated = awaitItem()
                assertEquals(2L, updated?.categoryId)
                assertEquals("🍲", updated?.categoryIcon)
                assertEquals("#123ABC", updated?.categoryColor)
                coVerify(exactly = 0) { transactionDao.updateMonotonic(any()) }
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_enrichCategoryInfo_when_observingById() = runTest {
            every { transactionDao.observeById(1L) } returns flowOf(sampleEntity)
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val result = repository.observeById(1L).first()

            assertNotNull(result)
            assertEquals("餐饮", result?.categoryName)
            assertEquals("ic_food", result?.categoryIcon)
            assertEquals("#FF5722", result?.categoryColor)
        }

        @Test
        fun should_returnNull_when_entityNotFound() = runTest {
            every { transactionDao.observeById(999L) } returns flowOf(null)

            val result = repository.observeById(999L).first()

            assertNull(result)
        }

        @Test
        fun should_returnNullCategoryName_when_categoryIdIsNull() = runTest {
            val entityNoCat = sampleEntity.copy(categoryId = null)
            val domainNoCat = sampleDomain.copy(categoryId = null)
            every { transactionDao.observeById(1L) } returns flowOf(entityNoCat)
            every { mapper.toDomain(entityNoCat) } returns domainNoCat

            val result = repository.observeById(1L).first()

            assertNotNull(result)
            assertNull(result?.categoryName)
        }
    }

    // ── observeByDateRange ───────────────────────────────────────────────

    @Nested
    inner class ObserveByDateRange {

        @Test
        fun should_enrichCategoryInfo_when_observingByDateRange() = runTest {
            every {
                transactionDao.observeByDateRange(any(), any())
            } returns flowOf(listOf(sampleEntity))
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val start = LocalDateTime.of(2026, 3, 1, 0, 0)
            val end = LocalDateTime.of(2026, 3, 31, 23, 59, 59)
            val result = repository.observeByDateRange(start, end).first()

            assertEquals(1, result.size)
            assertEquals("餐饮", result[0].categoryName)
            assertEquals("ic_food", result[0].categoryIcon)
            assertEquals("#FF5722", result[0].categoryColor)
        }

        @Test
        fun should_returnEmptyList_when_noTransactionsInRange() = runTest {
            every {
                transactionDao.observeByDateRange(any(), any())
            } returns flowOf(emptyList())

            val start = LocalDateTime.of(2026, 1, 1, 0, 0)
            val end = LocalDateTime.of(2026, 1, 31, 23, 59, 59)
            val result = repository.observeByDateRange(start, end).first()

            assertTrue(result.isEmpty())
        }
    }

    // ── observePendingTransactions ────────────────────────────────────────

    @Nested
    inner class ObservePendingTransactions {

        @Test
        fun should_enrichCategoryInfo_when_observingPending() = runTest {
            val pendingEntity = sampleEntity.copy(status = "PENDING")
            val pendingDomain = sampleDomain.copy(status = TransactionStatus.PENDING)
            every {
                transactionDao.observeByStatus("PENDING")
            } returns flowOf(listOf(pendingEntity))
            every { mapper.toDomain(pendingEntity) } returns pendingDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val result = repository.observePendingTransactions().first()

            assertEquals(1, result.size)
            assertEquals("餐饮", result[0].categoryName)
            assertEquals("ic_food", result[0].categoryIcon)
            assertEquals("#FF5722", result[0].categoryColor)
        }

        @Test
        fun should_returnEmptyList_when_noPendingTransactions() = runTest {
            every {
                transactionDao.observeByStatus("PENDING")
            } returns flowOf(emptyList())

            val result = repository.observePendingTransactions().first()

            assertTrue(result.isEmpty())
        }
    }

    // ── observeByCategoryAndMonth ─────────────────────────────────────────

    @Nested
    inner class ObserveByCategoryAndMonth {

        @Test
        fun should_enrichCategoryInfo_when_observingByCategoryAndMonth() = runTest {
            every {
                transactionDao.observeByCategoryAndDateRange(eq(2L), any(), any())
            } returns flowOf(listOf(sampleEntity))
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val result = repository.observeByCategoryAndMonth(
                2L, java.time.YearMonth.of(2026, 3)
            ).first()

            assertEquals(1, result.size)
            assertEquals("餐饮", result[0].categoryName)
            assertEquals("ic_food", result[0].categoryIcon)
            assertEquals("#FF5722", result[0].categoryColor)
        }

        @Test
        fun should_returnEmptyList_when_noCategoryTransactions() = runTest {
            every {
                transactionDao.observeByCategoryAndDateRange(eq(5L), any(), any())
            } returns flowOf(emptyList())

            val result = repository.observeByCategoryAndMonth(
                5L, java.time.YearMonth.of(2026, 3)
            ).first()

            assertTrue(result.isEmpty())
        }
    }

    // ── update ───────────────────────────────────────────────────────────

    @Nested
    inner class Update {

        @Test
        fun should_returnSuccess_when_updateSucceeds() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery { transactionDao.updateMonotonic(sampleEntity) } just Runs

            val result = repository.update(sampleDomain)

            assertTrue(result.isSuccess)
        }

        @Test
        fun should_returnFailure_when_updateThrows() = runTest {
            every { mapper.toEntity(sampleDomain) } returns sampleEntity
            coEvery {
                transactionDao.updateMonotonic(sampleEntity)
            } throws RuntimeException("update error")

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
        fun should_createSyncTombstone_when_deleting() = runTest {
            coEvery { transactionDao.softDeleteById(42L, any()) } just Runs

            val result = repository.delete(42L)

            assertTrue(result.isSuccess)
            coVerify { transactionDao.softDeleteById(42L, any()) }
        }

        @Test
        fun should_returnFailure_when_deleteThrows() = runTest {
            coEvery {
                transactionDao.softDeleteById(42L, any())
            } throws RuntimeException("delete error")

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
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val results = repository.search("星巴克")

            assertEquals(1, results.size)
            assertEquals("星巴克", results[0].merchantName)
        }

        @Test
        fun should_enrichCategoryInfo_when_searchFindsMatches() = runTest {
            coEvery { transactionDao.search("星巴克") } returns listOf(sampleEntity)
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val results = repository.search("星巴克")

            assertEquals(1, results.size)
            assertEquals("餐饮", results[0].categoryName)
            assertEquals("ic_food", results[0].categoryIcon)
            assertEquals("#FF5722", results[0].categoryColor)
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
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val results = repository.getPendingSync()

            assertEquals(1, results.size)
        }

        @Test
        fun should_enrichCategoryInfo_when_getPendingSync() = runTest {
            coEvery { transactionDao.getPendingSyncTransactions() } returns listOf(sampleEntity)
            every { mapper.toDomain(sampleEntity) } returns sampleDomain
            coEvery { categoryDao.getById(2L) } returns sampleCategory

            val results = repository.getPendingSync()

            assertEquals(1, results.size)
            assertEquals("餐饮", results[0].categoryName)
            assertEquals("ic_food", results[0].categoryIcon)
            assertEquals("#FF5722", results[0].categoryColor)
        }

        @Test
        fun should_updateSyncStatus_when_markSynced() = runTest {
            coEvery { transactionDao.updateSyncStatus(any(), "SYNCED") } just Runs

            repository.markSynced(listOf(1L, 2L))

            coVerify { transactionDao.updateSyncStatus(1L, "SYNCED") }
            coVerify { transactionDao.updateSyncStatus(2L, "SYNCED") }
        }

        @Test
        fun should_acknowledgeOnlyExactUploadedSnapshot() = runTest {
            coEvery {
                transactionDao.acknowledgeSync(
                    sampleDomain.syncId,
                    any(),
                    0,
                    12,
                    "UNSPECIFIED",
                    null,
                    "user-1",
                    "Cloud Name",
                    "cloud@example.test"
                )
            } returns 1

            val acknowledged = repository.acknowledgeSynced(
                sampleDomain.syncId,
                sampleDomain.updatedAt,
                0,
                12,
                null,
                "user-1",
                "Cloud Name",
                "cloud@example.test"
            )

            assertTrue(acknowledged)
            coVerify {
                transactionDao.acknowledgeSync(
                    sampleDomain.syncId,
                    any(),
                    0,
                    12,
                    "UNSPECIFIED",
                    null,
                    "user-1",
                    "Cloud Name",
                    "cloud@example.test"
                )
            }
        }

        @Test
        fun should_rebasePendingVersion_withoutChangingLocalPayload() = runTest {
            coEvery {
                transactionDao.rebasePendingSync(sampleDomain.syncId, 3, 8)
            } returns 1

            assertTrue(repository.rebasePendingSync(sampleDomain.syncId, 3, 8))

            coVerify {
                transactionDao.rebasePendingSync(sampleDomain.syncId, 3, 8)
            }
        }

        @Test
        fun should_mapRemoteCategoryByName_beforeMerging() = runTest {
            val remote = sampleDomain.copy(
                id = 0,
                categoryId = 999,
                categoryName = "餐饮",
                serverVersion = 12,
                syncStatus = SyncStatus.SYNCED,
                projectIds = emptyList(),
                recordedByUserId = "member-7",
                recordedByDisplayName = "Member Name",
                recordedByEmail = "member@example.test"
            )
            val mapped = sampleEntity.copy(
                id = 0,
                categoryId = 2,
                serverVersion = 12,
                syncStatus = "SYNCED",
                projectIdsState = "EXPLICIT",
                projectIdsBlob = "",
                recordedByUserId = "member-7",
                recordedByDisplayName = "Member Name",
                recordedByEmail = "member@example.test"
            )
            coEvery { categoryDao.resolveRemoteCategory("餐饮", "EXPENSE", any(), any()) } returns 2L
            every {
                mapper.toEntity(remote.copy(categoryId = 2))
            } returns mapped
            coEvery { transactionDao.mergeRemote(mapped) } returns true

            assertTrue(repository.mergeRemote(remote))

            coVerify { transactionDao.mergeRemote(mapped) }
        }

        @Test
        fun should_reportWhetherHistoricalRecordedByMetadataNeedsRefresh() = runTest {
            coEvery { transactionDao.hasSyncedTransactionsMissingRecordedBy() } returns true

            assertTrue(repository.needsRecordedByMetadataRefresh())

            coVerify { transactionDao.hasSyncedTransactionsMissingRecordedBy() }
        }

        @Test
        fun should_refreshRecordedByMetadata_onlyForMatchingSyncedRows() = runTest {
            val remote = sampleDomain.copy(
                recordedByUserId = "member-7",
                recordedByDisplayName = "Member Name",
                recordedByEmail = "member@example.test"
            )
            coEvery {
                transactionDao.refreshRecordedByMetadata(
                    remote.syncId,
                    "member-7",
                    "Member Name",
                    "member@example.test"
                )
            } returns 1

            assertTrue(repository.refreshRecordedByMetadata(remote))

            coVerify {
                transactionDao.refreshRecordedByMetadata(
                    remote.syncId,
                    "member-7",
                    "Member Name",
                    "member@example.test"
                )
            }
        }

        @Test
        fun should_reportWhetherHistoricalProjectMetadataNeedsRefresh() = runTest {
            coEvery { transactionDao.hasSyncedTransactionsMissingProjectMetadata() } returns true

            assertTrue(repository.needsProjectMetadataRefresh())

            coVerify { transactionDao.hasSyncedTransactionsMissingProjectMetadata() }
        }

        @Test
        fun should_refreshProjectMetadata_onlyForMatchingSyncedRows() = runTest {
            val remote = sampleDomain.copy(projectIds = listOf("project-a", "project-b"))
            coEvery {
                transactionDao.refreshProjectMetadata(
                    remote.syncId,
                    "EXPLICIT",
                    "project-a\u001Fproject-b"
                )
            } returns 1

            assertTrue(repository.refreshProjectMetadata(remote))

            coVerify {
                transactionDao.refreshProjectMetadata(
                    remote.syncId,
                    "EXPLICIT",
                    "project-a\u001Fproject-b"
                )
            }
        }
    }
}
