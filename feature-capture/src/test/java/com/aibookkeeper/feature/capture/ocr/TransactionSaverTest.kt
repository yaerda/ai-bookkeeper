package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
        fun `should return -1 when repository create fails`() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.failure(RuntimeException("DB error"))

            val result = saver.saveOne(makeItem())

            assertEquals(-1L, result)
        }
    }

    @Nested
    inner class SaveAll {

        @Test
        fun `should save all items in split mode`() = runTest {
            var nextId = 100L
            coEvery { transactionRepository.create(any()) } answers { Result.success(nextId++) }

            val items = listOf(
                makeItem(amount = 26.0, note = "马桶"),
                makeItem(amount = 6.9, note = "饮料"),
                makeItem(amount = 15.8, note = "牙刷")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(3, count)
            assertEquals(48.7, total, 0.01)
            coVerify(exactly = 3) { transactionRepository.create(any()) }
        }

        @Test
        fun `should handle mixed EXPENSE and INCOME items`() = runTest {
            val txSlots = mutableListOf<Transaction>()
            var nextId = 200L
            coEvery { transactionRepository.create(capture(slot<Transaction>().also { txSlots })) } answers {
                Result.success(nextId++)
            }
            // Re-mock to capture all
            coEvery { transactionRepository.create(any()) } answers { Result.success(nextId++) }

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
            val savedTransactions = mutableListOf<Transaction>()
            coEvery { categoryDao.findByNameAndType("工资", "INCOME") } returns salaryCategory
            coEvery { transactionRepository.create(capture(savedTransactions)) } answers {
                Result.success(savedTransactions.size.toLong())
            }

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
            val savedTransactions = mutableListOf<Transaction>()
            coEvery { transactionRepository.create(capture(savedTransactions)) } answers {
                Result.success(savedTransactions.size.toLong())
            }

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
            var nextId = 300L
            coEvery { transactionRepository.create(any()) } answers { Result.success(nextId++) }

            val items = listOf(
                makeItem(amount = 26.0, note = "商品A"),
                makeItem(amount = 0.0, note = "免费品"),
                makeItem(amount = 15.0, note = "商品B")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(2, count) // skipped the 0-amount item
            assertEquals(41.0, total, 0.01)
            coVerify(exactly = 2) { transactionRepository.create(any()) }
        }

        @Test
        fun `should handle negative amounts in split items`() = runTest {
            var nextId = 400L
            coEvery { transactionRepository.create(any()) } answers { Result.success(nextId++) }

            val items = listOf(
                makeItem(amount = -26.0, type = "EXPENSE"),
                makeItem(amount = -6.9, type = "EXPENSE")
            )

            val (count, total) = saver.saveAll(items)

            assertEquals(2, count)
            assertEquals(32.9, total, 0.01) // abs(-26) + abs(-6.9)
        }

        @Test
        fun `should return zero count when all items fail`() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.failure(RuntimeException("DB error"))

            val items = listOf(
                makeItem(amount = 26.0),
                makeItem(amount = 6.9)
            )

            val (count, _) = saver.saveAll(items)

            assertEquals(0, count)
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
