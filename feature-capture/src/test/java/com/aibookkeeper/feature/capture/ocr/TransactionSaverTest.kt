package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.ProjectWriteDestination
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TransactionSaverTest {

    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryDao: CategoryDao
    private lateinit var saver: TransactionSaver
    private val batchTransactions = mutableListOf<Transaction>()

    private val foodCategory = CategoryEntity(
        id = 1, name = "餐饮", icon = "ic_food", color = "#FF5722",
        type = "EXPENSE", isSystem = true, sortOrder = 1
    )
    private val otherExpenseCategory = CategoryEntity(
        id = 10, name = "其他", icon = "ic_other", color = "#607D8B",
        type = "EXPENSE", isSystem = true, sortOrder = 10
    )
    private val salaryCategory = CategoryEntity(
        id = 11, name = "工资", icon = "ic_salary", color = "#4CAF50",
        type = "INCOME", isSystem = true, sortOrder = 1
    )
    private val otherIncomeCategory = CategoryEntity(
        id = 16, name = "其他", icon = "ic_other_income", color = "#607D8B",
        type = "INCOME", isSystem = true, sortOrder = 6
    )

    @BeforeEach
    fun setup() {
        transactionRepository = mockk()
        categoryDao = mockk()
        saver = TransactionSaver(transactionRepository, categoryDao)
        batchTransactions.clear()
        coEvery { transactionRepository.createAllValidated(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            val transactions = firstArg<List<Transaction>>()
            batchTransactions.addAll(transactions)
            Result.success(transactions.indices.map { it + 1L })
        }

        // Default: category found
        coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } returns foodCategory
        coEvery { categoryDao.findByNameAndType("其他", "EXPENSE") } returns otherExpenseCategory
        coEvery { categoryDao.findByNameAndType("工资", "INCOME") } returns salaryCategory
        coEvery { categoryDao.findByNameAndType("其他", "INCOME") } returns otherIncomeCategory
        // Unknown categories fall back
        coEvery { categoryDao.findByNameAndType(neq("餐饮"), eq("EXPENSE")) } returns null
        coEvery { categoryDao.findByNameAndType("其他", "EXPENSE") } returns otherExpenseCategory
    }

    private fun makeItem(
        amount: Double? = 26.0,
        type: String = "EXPENSE",
        category: String = "餐饮",
        note: String? = "测试商品",
        date: String = "2026-03-17",
        confidence: Float = 0.9f
    ) = ExtractionResult(
        amount = amount,
        type = type,
        category = category,
        merchantName = "测试商家",
        date = date,
        note = note,
        confidence = confidence,
        source = ExtractionSource.AZURE_AI
    )

    @Nested
    inner class SaveOne {

        @Test
        fun `should save positive expense amount successfully`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(1L)

            val result = saver.saveOne(makeItem(amount = 26.0, type = "EXPENSE"))

            assertEquals(1L, result)
            assertEquals(26.0, txSlot.captured.amount)
            assertEquals(com.aibookkeeper.core.data.model.TransactionType.EXPENSE, txSlot.captured.type)
        }

        @Test
        fun `should save positive income amount successfully`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { categoryDao.findByNameAndType("工资", "INCOME") } returns salaryCategory
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(2L)

            val result = saver.saveOne(makeItem(amount = 5000.0, type = "INCOME", category = "工资"))

            assertEquals(2L, result)
            assertEquals(5000.0, txSlot.captured.amount)
            assertEquals(com.aibookkeeper.core.data.model.TransactionType.INCOME, txSlot.captured.type)
            assertEquals(salaryCategory.id, txSlot.captured.categoryId)
        }

        @Test
        fun `should convert negative amount to positive using abs`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(3L)

            val result = saver.saveOne(makeItem(amount = -26.0, type = "EXPENSE"))

            assertEquals(3L, result)
            assertEquals(26.0, txSlot.captured.amount) // abs(-26) = 26
        }

        @Test
        fun `should skip item with zero amount`() = runTest {
            val result = saver.saveOne(makeItem(amount = 0.0))

            assertEquals(-1L, result)
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `should skip item with null amount`() = runTest {
            val result = saver.saveOne(makeItem(amount = null))

            assertEquals(-1L, result)
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `should fallback to other category when category not found`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { categoryDao.findByNameAndType("饮料", "EXPENSE") } returns null
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(4L)

            val result = saver.saveOne(makeItem(category = "饮料"))

            assertEquals(4L, result)
            assertEquals(otherExpenseCategory.id, txSlot.captured.categoryId)
        }

        @Test
        fun `should fallback to EXPENSE when type is invalid`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(5L)

            val result = saver.saveOne(makeItem(type = "INVALID_TYPE"))

            assertEquals(5L, result)
            assertEquals(com.aibookkeeper.core.data.model.TransactionType.EXPENSE, txSlot.captured.type)
        }

        @Test
        fun `should use current datetime when date is invalid`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(6L)

            val result = saver.saveOne(makeItem(date = "invalid-date"))

            assertEquals(6L, result)
            // Should not throw, date falls back to now
            assertTrue(txSlot.captured.date.year >= 2026)
        }

        @Test
        fun `date-only capture keeps the recording time and selected date`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(7L)

            saver.saveOne(makeItem(date = "2026-08-31"))

            assertEquals(java.time.LocalDate.of(2026, 8, 31), txSlot.captured.date.toLocalDate())
            assertEquals(txSlot.captured.createdAt.toLocalTime(), txSlot.captured.date.toLocalTime())
        }

        @Test
        fun `should forward explicit project ids when capture user selects them`() = runTest {
            val txSlot = slot<Transaction>()
            coEvery { transactionRepository.create(capture(txSlot)) } returns Result.success(8L)

            saver.saveOne(makeItem(), projectIds = listOf("project-a", "project-b"))

            assertEquals(listOf("project-a", "project-b"), txSlot.captured.projectIds)
        }

        @Test
        fun `should return -1 when repository create fails`() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.failure(RuntimeException("DB error"))

            val result = saver.saveOne(makeItem())

            assertEquals(-1L, result)
        }

        @Test
        fun `destination remains default Room with local category IDs and explicit opt out`() = runTest {
            val projects = mockk<ProjectRepository>()
            val target = ProjectWriteDestination("original", "default", defaultRoom = true)
            every { projects.captureDestination(true) } returns target
            every { projects.requireCurrentDestination(target) } returns Unit
            val saved = slot<Transaction>()
            coEvery { transactionRepository.create(capture(saved)) } returns Result.success(1)
            TransactionSaver(transactionRepository, categoryDao, projects).saveOne(
                makeItem(), projectIds = emptyList()
            )
            assertEquals(target, saved.captured.projectDestination)
            assertEquals(foodCategory.id, saved.captured.categoryId)
            assertEquals(emptyList<String>(), saved.captured.projectIds)
            assertEquals(com.aibookkeeper.core.data.model.TransactionSource.AUTO_CAPTURE, saved.captured.source)
            verify(exactly = 1) { projects.captureDestination(true) }
        }

        @Test
        fun `account change during category lookup cannot persist prior explicit picks`() = runTest {
            val projects = mockk<ProjectRepository>()
            val target = ProjectWriteDestination("original", "default", defaultRoom = true)
            var changed = false
            every { projects.captureDestination(true) } returns target
            every { projects.requireCurrentDestination(target) } answers {
                if (changed) throw LedgerSelectionChangedException()
            }
            coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } answers {
                changed = true
                foodCategory
            }
            val failure = runCatching {
                TransactionSaver(transactionRepository, categoryDao, projects)
                    .saveOne(makeItem(), projectIds = listOf("picked"))
            }.exceptionOrNull()
            assertTrue(failure is LedgerSelectionChangedException)
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `cancellation is not reported as failed save`() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.failure(CancellationException())
            assertTrue(runCatching { saver.saveOne(makeItem()) }.exceptionOrNull() is CancellationException)
        }
    }

    @Nested
    inner class EditedBatch {

        @Test
        fun `repository receives only retained edited rows with categories and shared date`() = runTest {
            val saved = batchTransactions
            val originalLines = listOf("删除项 -¥100.00", "午餐 -¥26.00", "工资 +¥50.00")
            val originalItems = listOf(
                makeItem(amount = 100.0, note = "删除项"),
                makeItem(amount = 26.0, note = "午餐"),
                makeItem(amount = 50.0, type = "INCOME", category = "工资", note = "工资")
            )
            val text = "午餐 -¥28.50\n工资 +¥55.00"
            val edited = applyCaptureTextEdits(
                text, originalLines, originalItems, makeItem(amount = 176.0, date = "2026-09-06")
            )

            val result = saver.saveAll(edited.items, text, edited.summary?.date)

            assertEquals(2 to 83.5, result)
            assertEquals(listOf("午餐", "工资"), saved.map { it.note })
            assertEquals(listOf(28.5, 55.0), saved.map { it.amount })
            assertEquals(listOf(foodCategory.id, salaryCategory.id), saved.map { it.categoryId })
            assertEquals(listOf("EXPENSE", "INCOME"), saved.map { it.type.name })
            assertTrue(saved.all { it.date.toLocalDate().toString() == "2026-09-06" })
            assertTrue(saved.all { it.originalInput == text })
            coVerify(exactly = 1) { transactionRepository.createAllValidated(any(), any()) }
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `deleting every recognized row causes no repository writes`() = runTest {
            val edited = applyCaptureTextEdits(
                "", listOf("午餐 ¥26.00"), listOf(makeItem()), makeItem()
            )

            assertEquals(0 to 0.0, saver.saveAll(edited.items))
            coVerify(exactly = 0) { transactionRepository.create(any()) }
            coVerify(exactly = 0) { transactionRepository.createAllValidated(any(), any()) }
        }
    }

    @Nested
    inner class SaveAll {

        @Test
        fun `should save all items in split mode`() = runTest {
            val items = listOf(
                makeItem(amount = 26.0, note = "马桶"),
                makeItem(amount = 6.9, note = "饮料"),
                makeItem(amount = 15.8, note = "牙刷")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(3, count)
            assertEquals(48.7, total, 0.01)
            coVerify(exactly = 1) { transactionRepository.createAllValidated(any(), any()) }
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `should handle mixed EXPENSE and INCOME items`() = runTest {
            val items = listOf(
                makeItem(amount = 100.0, type = "EXPENSE", note = "消费"),
                makeItem(amount = 50.0, type = "INCOME", category = "工资", note = "退款")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(2, count)
            assertEquals(150.0, total, 0.01) // abs(100) + abs(50)
        }

        @Test
        fun `should save negative expenses and positive incomes in split mode`() = runTest {
            val savedTransactions = batchTransactions
            coEvery { categoryDao.findByNameAndType("工资", "INCOME") } returns salaryCategory

            val items = listOf(
                makeItem(amount = -26.0, type = "EXPENSE", note = "午餐"),
                makeItem(amount = 50.0, type = "INCOME", category = "工资", note = "退款")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(2, count)
            assertEquals(76.0, total, 0.01)
            assertEquals(26.0, savedTransactions[0].amount)
            assertEquals(com.aibookkeeper.core.data.model.TransactionType.EXPENSE, savedTransactions[0].type)
            assertEquals(50.0, savedTransactions[1].amount)
            assertEquals(com.aibookkeeper.core.data.model.TransactionType.INCOME, savedTransactions[1].type)
        }

        @Test
        fun `should use shared visible date for split items`() = runTest {
            val savedTransactions = batchTransactions

            val items = listOf(
                makeItem(amount = 26.0, type = "EXPENSE", date = "2026-02-19", note = "商品A"),
                makeItem(amount = 15.0, type = "EXPENSE", date = "2026-02-19", note = "商品B")
            )

            val (count, total) = saver.saveAll(
                items = items,
                overrideDate = "2026-03-17"
            )

            assertEquals(2, count)
            assertEquals(41.0, total, 0.01)
            assertEquals(2026, savedTransactions[0].date.year)
            assertEquals(3, savedTransactions[0].date.monthValue)
            assertEquals(17, savedTransactions[0].date.dayOfMonth)
            assertEquals(2026, savedTransactions[1].date.year)
            assertEquals(3, savedTransactions[1].date.monthValue)
            assertEquals(17, savedTransactions[1].date.dayOfMonth)
        }

        @Test
        fun `should skip zero-amount items but save others`() = runTest {
            val items = listOf(
                makeItem(amount = 26.0, note = "商品A"),
                makeItem(amount = 0.0, note = "免费品"),
                makeItem(amount = 15.0, note = "商品B")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(2, count) // skipped the 0-amount item
            assertEquals(41.0, total, 0.01)
            assertEquals(2, batchTransactions.size)
            coVerify(exactly = 1) { transactionRepository.createAllValidated(any(), any()) }
        }

        @Test
        fun `should handle negative amounts in split items`() = runTest {
            val items = listOf(
                makeItem(amount = -26.0, type = "EXPENSE"),
                makeItem(amount = -6.9, type = "EXPENSE")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(2, count)
            assertEquals(32.9, total, 0.01) // abs(-26) + abs(-6.9)
        }

        @Test
        fun `batch failure is surfaced without a success-shaped zero count`() = runTest {
            coEvery { transactionRepository.createAllValidated(any(), any()) } returns
                Result.failure(IllegalStateException("DB error"))

            val items = listOf(
                makeItem(amount = 26.0),
                makeItem(amount = 6.9)
            )

            val error = runCatching { saver.saveAll(items) }.exceptionOrNull()
            assertEquals("DB error", error?.message)
            assertTrue(batchTransactions.isEmpty())
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `destination invalidation aborts the single atomic batch call`() = runTest {
            val projects = mockk<ProjectRepository>()
            val target = ProjectWriteDestination("account", "default", defaultRoom = true)
            every { projects.captureDestination(true) } returns target
            every { projects.requireCurrentDestination(target) } returns Unit
            coEvery { transactionRepository.createAllValidated(any(), any()) } returns
                Result.failure(LedgerSelectionChangedException())
            val error = runCatching {
                TransactionSaver(transactionRepository, categoryDao, projects)
                    .saveAll(listOf(makeItem(), makeItem(amount = 15.0)), projectIds = emptyList())
            }.exceptionOrNull()
            assertTrue(error is LedgerSelectionChangedException)
            coVerify(exactly = 1) { transactionRepository.createAllValidated(any(), any()) }
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }

        @Test
        fun `should return zero count for empty list`() = runTest {
            val (count, total) = saver.saveAll(emptyList())

            assertEquals(0, count)
            assertEquals(0.0, total, 0.01)
            coVerify(exactly = 0) { transactionRepository.create(any()) }
        }
    }
}
