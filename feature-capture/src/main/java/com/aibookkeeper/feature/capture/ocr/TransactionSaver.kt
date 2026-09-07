package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.common.extensions.resolveTransactionDate

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.ProjectWriteDestination
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime

/**
 * Extracted save logic from CaptureScreen for testability.
 * Handles single and batch transaction saving from ExtractionResult.
 */
class TransactionSaver(
    private val transactionRepository: TransactionRepository,
    private val categoryDao: CategoryDao,
    private val projectRepository: ProjectRepository? = null
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
        overrideDate: String? = null,
        projectIds: List<String>? = null,
        destination: ProjectWriteDestination? = null
    ): Long {
        val target = destination ?: projectRepository?.captureDestination(defaultRoom = true)
        target?.let { projectRepository?.requireCurrentDestination(it) }
        val transaction = prepareTransaction(data, originalInput, overrideDate, projectIds, target) ?: return -1L
        target?.let { projectRepository?.requireCurrentDestination(it) }
        return transactionRepository.create(transaction).getOrElse {
            if (it is CancellationException) throw it
            -1L
        }
    }

    private suspend fun prepareTransaction(
        data: ExtractionResult,
        originalInput: String,
        overrideDate: String?,
        projectIds: List<String>?,
        target: ProjectWriteDestination?
    ): Transaction? {
        val amount = Math.abs(data.amount ?: 0.0)
        require(amount.isFinite()) { "金额无效，请核对后保存" }
        if (amount == 0.0) return null
        val type = try {
            TransactionType.valueOf(data.type)
        } catch (_: IllegalArgumentException) {
            TransactionType.EXPENSE
        }
        val categoryId = categoryDao.findByNameAndType(data.category, type.name)?.id
            ?: categoryDao.findByNameAndType("其他", type.name)?.id
        val now = LocalDateTime.now()
        val parsedDate = resolveTransactionDate(overrideDate ?: data.date, now)
        return Transaction(
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
            status = if (data.confidence >= 0.7f) TransactionStatus.CONFIRMED else TransactionStatus.PENDING,
            syncStatus = SyncStatus.LOCAL,
            aiConfidence = data.confidence,
            projectIds = projectIds?.toList(),
            projectDestination = target
        )
    }

    /**
     * Save multiple ExtractionResults (split mode).
     * Valid rows commit together, or none do. Returns (successCount, totalAmount).
     */
    suspend fun saveAll(
        items: List<ExtractionResult>,
        originalInput: String = "",
        overrideDate: String? = null,
        projectIds: List<String>? = null,
        destination: ProjectWriteDestination? = null
    ): Pair<Int, Double> {
        if (items.isEmpty()) return 0 to 0.0
        val target = destination ?: projectRepository?.captureDestination(defaultRoom = true)
        val selectedProjectIds = projectIds?.toList()
        val transactions = mutableListOf<Transaction>()
        for (item in items) {
            target?.let { projectRepository?.requireCurrentDestination(it) }
            prepareTransaction(item, originalInput, overrideDate, selectedProjectIds, target)?.let {
                transactions += it
            }
        }
        if (transactions.isEmpty()) return 0 to 0.0
        val savedIds = transactionRepository.createAllValidated(transactions) {
            target?.let { projectRepository?.requireCurrentDestination(it) }
        }.getOrThrow()
        return savedIds.size to transactions.sumOf { it.amount }
    }
}
