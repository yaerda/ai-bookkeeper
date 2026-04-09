package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionRepository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Extracted save logic from CaptureScreen for testability.
 * Handles single and batch transaction saving from ExtractionResult.
 */
class TransactionSaver(
    private val transactionRepository: TransactionRepository,
    private val categoryDao: CategoryDao
) {
    /**
     * Save a single ExtractionResult as a Transaction.
     * Returns the transaction ID (>0) on success, -1 on failure.
     *
     * - Amount is always stored as positive (abs value)
     * - Type (EXPENSE/INCOME) determines direction
     * - Items with amount == 0 are skipped
     */
    suspend fun saveOne(
        data: ExtractionResult,
        originalInput: String = "",
        overrideDate: String? = null
    ): Long {
        val type = try {
            TransactionType.valueOf(data.type)
        } catch (_: Exception) {
            TransactionType.EXPENSE
        }
        val categoryId = categoryDao.findByNameAndType(data.category, type.name)?.id
            ?: categoryDao.findByNameAndType("其他", type.name)?.id
        val now = LocalDateTime.now()
        val parsedDate = try {
            LocalDate.parse(overrideDate ?: data.date).atStartOfDay()
        } catch (_: Exception) {
            now
        }
        val amount = Math.abs(data.amount ?: 0.0)

        if (amount == 0.0) return -1L

        return transactionRepository.create(
            Transaction(
                amount = amount,
                type = type,
                categoryId = categoryId,
                merchantName = data.merchantName,
                note = data.note,
                originalInput = originalInput.ifBlank { "AI Vision: image" },
                date = parsedDate,
                createdAt = now,
                updatedAt = now,
                source = TransactionSource.AUTO_CAPTURE,
                status = if (data.confidence >= 0.7f)
                    TransactionStatus.CONFIRMED
                else
                    TransactionStatus.PENDING,
                syncStatus = SyncStatus.LOCAL,
                aiConfidence = data.confidence
            )
        ).getOrElse { -1L }
    }

    /**
     * Save multiple ExtractionResults (split mode).
     * Returns pair of (successCount, totalAmount).
     */
    suspend fun saveAll(
        items: List<ExtractionResult>,
        originalInput: String = "",
        overrideDate: String? = null
    ): Pair<Int, Double> {
        var successCount = 0
        var totalAmount = 0.0
        for (item in items) {
            val txId = saveOne(item, originalInput, overrideDate)
            if (txId > 0) {
                successCount++
                totalAmount += Math.abs(item.amount ?: 0.0)
            }
        }
        return Pair(successCount, totalAmount)
    }
}
