package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aibookkeeper.core.data.local.entity.RawEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: RawEventEntity): Long

    @Update
    suspend fun update(event: RawEventEntity)

    @Query("UPDATE raw_events SET status = :status, transactionId = :transactionId WHERE id = :id")
    suspend fun markExtracted(id: Long, status: String = "EXTRACTED", transactionId: Long)

    @Query("UPDATE raw_events SET status = 'FAILED', extractionError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("SELECT * FROM raw_events WHERE status = 'PENDING' ORDER BY capturedAt ASC")
    suspend fun getPendingEvents(): List<RawEventEntity>

    @Query("SELECT * FROM raw_events WHERE status = 'FAILED' AND retryCount < :maxRetries ORDER BY capturedAt ASC")
    suspend fun getRetryableEvents(maxRetries: Int = 3): List<RawEventEntity>

    @Query("SELECT * FROM raw_events ORDER BY capturedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<RawEventEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM raw_events WHERE contentHash = :hash)")
    suspend fun existsByHash(hash: String): Boolean

    @Query("DELETE FROM raw_events WHERE capturedAt < :beforeMillis AND status IN ('EXTRACTED', 'IGNORED')")
    suspend fun cleanOldEvents(beforeMillis: Long)
}
