package com.aibookkeeper.core.data.ai

import android.util.Log
import com.aibookkeeper.core.common.extensions.toEpochMillis
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.TransactionEntity
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.RawEventStatus
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.repository.RawEventRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * End-to-end pipeline that processes raw captured events (notifications, OCR, etc.)
 * through AI extraction and stores the resulting transactions.
 */
class NotificationExtractionPipeline @Inject constructor(
    private val strategyManager: ExtractionStrategyManager,
    private val rawEventRepository: RawEventRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryDao: CategoryDao
) {
    companion object {
        private const val TAG = "NotifExtraction"
    }

    /**
     * Capture a notification and immediately attempt extraction.
     * @return The created transaction ID, or -1 if deduplicated/failed.
     */
    suspend fun processNotification(
        sourceApp: String,
        content: String
    ): Long = withContext(Dispatchers.IO) {
        // 1. Store raw event (with dedup)
        val eventId = rawEventRepository.captureEvent(sourceApp, content).getOrElse {
            Log.e(TAG, "Failed to store raw event", it)
            return@withContext -1L
        }
        if (eventId == -1L) {
            Log.d(TAG, "Duplicate notification ignored")
            return@withContext -1L
        }

        // 2. Extract via AI (online-first, fallback to local)
        val extractionResult = strategyManager.extract(content)

        if (extractionResult.isFailure) {
            val error = extractionResult.exceptionOrNull()?.message ?: "Unknown error"
            Log.w(TAG, "Extraction failed: $error")
            rawEventRepository.markFailed(eventId, error)
            return@withContext -1L
        }

        val data = extractionResult.getOrThrow()

        // 3. Resolve category ID
        val categoryId = resolveCategoryId(data)

        // 4. Create transaction
        val now = System.currentTimeMillis()
        val parsedDate = try {
            LocalDate.parse(data.date).atStartOfDay().toEpochMillis()
        } catch (e: Exception) {
            now
        }

        val txResult = transactionRepository.create(
            com.aibookkeeper.core.data.model.Transaction(
                amount = data.amount ?: 0.0,
                type = com.aibookkeeper.core.data.model.TransactionType.valueOf(data.type),
                categoryId = categoryId,
                merchantName = data.merchantName,
                note = data.note,
                originalInput = content,
                date = parsedDate.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                },
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now(),
                source = com.aibookkeeper.core.data.model.TransactionSource.AUTO_CAPTURE,
                status = if (data.confidence >= 0.7f)
                    TransactionStatus.CONFIRMED
                else
                    TransactionStatus.PENDING,
                syncStatus = com.aibookkeeper.core.data.model.SyncStatus.LOCAL,
                aiConfidence = data.confidence
            )
        )

        val transactionId = txResult.getOrElse {
            Log.e(TAG, "Failed to create transaction", it)
            rawEventRepository.markFailed(eventId, it.message ?: "DB error")
            return@withContext -1L
        }

        // 5. Mark raw event as extracted
        rawEventRepository.markExtracted(eventId, transactionId)

        Log.i(TAG, "Notification processed: $sourceApp → tx#$transactionId (${data.category} ¥${data.amount})")
        transactionId
    }

    /**
     * Retry failed/pending raw events.
     */
    suspend fun retryFailedEvents() = withContext(Dispatchers.IO) {
        val retryable = rawEventRepository.getRetryableEvents()
        Log.i(TAG, "Retrying ${retryable.size} failed events")

        for (event in retryable) {
            val result = strategyManager.extract(event.rawContent)
            if (result.isSuccess) {
                val data = result.getOrThrow()
                val categoryId = resolveCategoryId(data)
                val now = LocalDateTime.now()

                val txResult = transactionRepository.create(
                    com.aibookkeeper.core.data.model.Transaction(
                        amount = data.amount ?: 0.0,
                        type = com.aibookkeeper.core.data.model.TransactionType.valueOf(data.type),
                        categoryId = categoryId,
                        merchantName = data.merchantName,
                        note = data.note,
                        originalInput = event.rawContent,
                        date = now,
                        createdAt = now,
                        updatedAt = now,
                        source = com.aibookkeeper.core.data.model.TransactionSource.AUTO_CAPTURE,
                        status = TransactionStatus.PENDING,
                        syncStatus = com.aibookkeeper.core.data.model.SyncStatus.LOCAL,
                        aiConfidence = data.confidence
                    )
                )

                txResult.onSuccess { txId ->
                    rawEventRepository.markExtracted(event.id, txId)
                }.onFailure { e ->
                    rawEventRepository.markFailed(event.id, e.message ?: "DB error")
                }
            } else {
                rawEventRepository.markFailed(
                    event.id,
                    result.exceptionOrNull()?.message ?: "Retry failed"
                )
            }
        }
    }

    private suspend fun resolveCategoryId(data: ExtractionResult): Long? {
        val type = data.type // "INCOME" or "EXPENSE"
        return categoryDao.findByNameAndType(data.category, type)?.id
    }
}
