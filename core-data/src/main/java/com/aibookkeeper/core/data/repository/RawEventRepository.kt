package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.local.dao.RawEventDao
import com.aibookkeeper.core.data.local.entity.RawEventEntity
import com.aibookkeeper.core.data.model.RawEventStatus
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import javax.inject.Inject

interface RawEventRepository {
    suspend fun captureEvent(sourceApp: String, content: String): Result<Long>
    suspend fun getPendingEvents(): List<RawEventEntity>
    suspend fun getRetryableEvents(): List<RawEventEntity>
    suspend fun markExtracted(eventId: Long, transactionId: Long)
    suspend fun markFailed(eventId: Long, error: String)
    suspend fun isDuplicate(content: String): Boolean
    fun observeRecent(): Flow<List<RawEventEntity>>
    suspend fun cleanOldEvents(daysToKeep: Int = 30)
}

class RawEventRepositoryImpl @Inject constructor(
    private val rawEventDao: RawEventDao
) : RawEventRepository {

    override suspend fun captureEvent(sourceApp: String, content: String): Result<Long> = runCatching {
        val hash = computeHash(content)
        if (rawEventDao.existsByHash(hash)) {
            return Result.success(-1L) // deduplicated
        }
        rawEventDao.insert(
            RawEventEntity(
                sourceApp = sourceApp,
                rawContent = content,
                contentHash = hash,
                capturedAt = System.currentTimeMillis(),
                status = RawEventStatus.PENDING.name
            )
        )
    }

    override suspend fun getPendingEvents(): List<RawEventEntity> =
        rawEventDao.getPendingEvents()

    override suspend fun getRetryableEvents(): List<RawEventEntity> =
        rawEventDao.getRetryableEvents()

    override suspend fun markExtracted(eventId: Long, transactionId: Long) {
        rawEventDao.markExtracted(eventId, RawEventStatus.EXTRACTED.name, transactionId)
    }

    override suspend fun markFailed(eventId: Long, error: String) {
        rawEventDao.markFailed(eventId, error)
    }

    override suspend fun isDuplicate(content: String): Boolean =
        rawEventDao.existsByHash(computeHash(content))

    override fun observeRecent(): Flow<List<RawEventEntity>> =
        rawEventDao.observeRecent()

    override suspend fun cleanOldEvents(daysToKeep: Int) {
        val cutoff = System.currentTimeMillis() - daysToKeep * 24 * 60 * 60 * 1000L
        rawEventDao.cleanOldEvents(cutoff)
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
