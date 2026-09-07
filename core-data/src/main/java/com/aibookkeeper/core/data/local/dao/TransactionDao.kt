package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aibookkeeper.core.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    // === Insert ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @androidx.room.Transaction
    suspend fun insertValidated(transaction: TransactionEntity, beforePersist: () -> Unit): Long {
        beforePersist()
        return insert(transaction)
    }

    @androidx.room.Transaction
    suspend fun insertAllValidated(
        transactions: List<TransactionEntity>,
        beforePersist: () -> Unit
    ): List<Long> {
        check(transactions.map { it.syncId }.distinct().size == transactions.size) { "批量账目编号重复" }
        val ids = transactions.map { transaction ->
            beforePersist()
            insert(transaction).also { check(it > 0) { "批量账目保存失败" } }
        }
        beforePersist()
        return ids
    }

    @androidx.room.Transaction
    suspend fun updateValidated(transaction: TransactionEntity, beforePersist: () -> Unit) {
        beforePersist()
        updateMonotonic(transaction)
    }

    // === Update ===

    @Update
    suspend fun update(transaction: TransactionEntity)

    @androidx.room.Transaction
    suspend fun updateMonotonic(transaction: TransactionEntity) {
        updateMutablePreservingRemoteFields(
            id = transaction.id,
            amount = transaction.amount,
            type = transaction.type,
            categoryId = transaction.categoryId,
            merchantName = transaction.merchantName,
            note = transaction.note,
            originalInput = transaction.originalInput,
            date = transaction.date,
            createdAt = transaction.createdAt,
            updatedAt = transaction.updatedAt,
            source = transaction.source,
            status = transaction.status,
            syncStatus = transaction.syncStatus,
            aiConfidence = transaction.aiConfidence,
            projectIdsState = transaction.projectIdsState,
            projectIdsBlob = transaction.projectIdsBlob
        )
    }

    @Query(
        """
        UPDATE transactions
        SET amount = :amount,
            type = :type,
            categoryId = :categoryId,
            merchantName = :merchantName,
            note = :note,
            originalInput = :originalInput,
            date = :date,
            createdAt = :createdAt,
            updatedAt = CASE
                WHEN :updatedAt > updatedAt THEN :updatedAt
                ELSE updatedAt + 1
            END,
            projectIdsState = CASE
                WHEN :projectIdsState = 'UNSPECIFIED' THEN projectIdsState
                ELSE :projectIdsState
            END,
            projectIdsBlob = CASE
                WHEN :projectIdsState = 'UNSPECIFIED' THEN projectIdsBlob
                ELSE :projectIdsBlob
            END,
            projectIdsWriteState = CASE
                WHEN :projectIdsState = 'UNSPECIFIED' THEN projectIdsWriteState
                ELSE :projectIdsState
            END,
            projectIdsWriteBlob = CASE
                WHEN :projectIdsState = 'UNSPECIFIED' THEN projectIdsWriteBlob
                ELSE :projectIdsBlob
            END,
            projectIdsWriteUpdatedAt = CASE
                WHEN :projectIdsState = 'UNSPECIFIED' THEN projectIdsWriteUpdatedAt
                WHEN :updatedAt > updatedAt THEN :updatedAt
                ELSE updatedAt + 1
            END,
            source = :source,
            status = :status,
            syncStatus = :syncStatus,
            aiConfidence = :aiConfidence
        WHERE id = :id
        """
    )
    suspend fun updateMutablePreservingRemoteFields(
        id: Long,
        amount: Double,
        type: String,
        categoryId: Long?,
        merchantName: String?,
        note: String?,
        originalInput: String?,
        date: Long,
        createdAt: Long,
        updatedAt: Long,
        source: String,
        status: String,
        syncStatus: String,
        aiConfidence: Float?,
        projectIdsState: String,
        projectIdsBlob: String?
    )

    @Query("""
        UPDATE transactions
        SET status = :status,
            updatedAt = CASE
                WHEN :updatedAt > updatedAt THEN :updatedAt
                ELSE updatedAt + 1
            END,
            syncStatus = 'PENDING_SYNC'
        WHERE id = :id
    """)
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("""
        UPDATE transactions SET syncStatus = :syncStatus,
            projectIdsWriteState = CASE WHEN :syncStatus = 'SYNCED' THEN 'UNSPECIFIED' ELSE projectIdsWriteState END,
            projectIdsWriteBlob = CASE WHEN :syncStatus = 'SYNCED' THEN NULL ELSE projectIdsWriteBlob END,
            projectIdsWriteUpdatedAt = CASE WHEN :syncStatus = 'SYNCED' THEN 0 ELSE projectIdsWriteUpdatedAt END
        WHERE id = :id
    """)
    suspend fun updateSyncStatus(id: Long, syncStatus: String)

    // === Delete ===

    @Query("""
        UPDATE transactions
        SET deletedAt = CASE
                WHEN :deletedAt > updatedAt THEN :deletedAt
                ELSE updatedAt + 1
            END,
            updatedAt = CASE
                WHEN :deletedAt > updatedAt THEN :deletedAt
                ELSE updatedAt + 1
            END,
            syncStatus = 'PENDING_SYNC'
        WHERE id = :id
    """)
    suspend fun softDeleteById(id: Long, deletedAt: Long)

    // === Query - Single ===

    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: Long): Flow<TransactionEntity?>

    // === Query - List (reactive) ===

    @Query("""
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND date BETWEEN :startMillis AND :endMillis
        ORDER BY date DESC, createdAt DESC, syncId DESC
    """)
    fun observeByDateRange(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND date BETWEEN :startMillis AND :endMillis AND type = :type
        ORDER BY date DESC, createdAt DESC, syncId DESC
    """)
    fun observeByDateRangeAndType(
        startMillis: Long, endMillis: Long, type: String
    ): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND status = :status
        ORDER BY createdAt DESC
    """)
    fun observeByStatus(status: String): Flow<List<TransactionEntity>>

    @Query("""
        SELECT date FROM transactions
        WHERE deletedAt IS NULL
        ORDER BY date DESC
    """)
    fun observeActiveTransactionDates(): Flow<List<Long>>

    @Query("""
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND (
            categoryId = :categoryId
            OR (:categoryId IS NULL AND categoryId IS NULL)
          )
          AND date BETWEEN :startMillis AND :endMillis
        ORDER BY date DESC, createdAt DESC, syncId DESC
    """)
    fun observeByCategoryAndDateRange(
        categoryId: Long?, startMillis: Long, endMillis: Long
    ): Flow<List<TransactionEntity>>

    // === Aggregate queries ===

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions
        WHERE deletedAt IS NULL AND type = :type
          AND date BETWEEN :startMillis AND :endMillis
    """)
    suspend fun sumByTypeAndDateRange(type: String, startMillis: Long, endMillis: Long): Double

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions
        WHERE deletedAt IS NULL AND type = :type
          AND date BETWEEN :startMillis AND :endMillis
    """)
    fun observeSumByTypeAndDateRange(
        type: String, startMillis: Long, endMillis: Long
    ): Flow<Double>

    @Query("""
        SELECT categoryId, SUM(amount) as total
        FROM transactions
        WHERE deletedAt IS NULL AND type = 'EXPENSE'
          AND date BETWEEN :startMillis AND :endMillis
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    fun observeExpenseBreakdown(
        startMillis: Long, endMillis: Long
    ): Flow<List<CategorySum>>

    @Query("""
        SELECT COUNT(*) FROM transactions
        WHERE deletedAt IS NULL AND date BETWEEN :startMillis AND :endMillis
    """)
    suspend fun countByDateRange(startMillis: Long, endMillis: Long): Int

    // === Trends ===

    @Query("""
        SELECT categoryId, SUM(amount) as total
        FROM transactions
        WHERE deletedAt IS NULL AND type = :type
          AND date BETWEEN :startMillis AND :endMillis
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryBreakdown(type: String, startMillis: Long, endMillis: Long): List<CategorySum>

    // === Sync ===

    @Query("SELECT * FROM transactions WHERE syncStatus != 'SYNCED' ORDER BY updatedAt ASC")
    suspend fun getPendingSyncTransactions(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncStatus != 'SYNCED'")
    fun observePendingSyncCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): TransactionEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM transactions
            WHERE syncStatus = 'SYNCED'
              AND recordedByUserId IS NULL
              AND recordedByDisplayName IS NULL
              AND recordedByEmail IS NULL
            LIMIT 1
        )
        """
    )
    suspend fun hasSyncedTransactionsMissingRecordedBy(): Boolean

    @Query("""
        UPDATE transactions
        SET serverVersion = :serverVersion,
            projectIdsState = CASE
                WHEN projectIdsWriteUpdatedAt <= :expectedUpdatedAt THEN :projectIdsState
                ELSE projectIdsState
            END,
            projectIdsBlob = CASE
                WHEN projectIdsWriteUpdatedAt <= :expectedUpdatedAt THEN :projectIdsBlob
                ELSE projectIdsBlob
            END,
            projectIdsWriteState = CASE
                WHEN projectIdsWriteUpdatedAt <= :expectedUpdatedAt THEN 'UNSPECIFIED'
                ELSE projectIdsWriteState
            END,
            projectIdsWriteBlob = CASE
                WHEN projectIdsWriteUpdatedAt <= :expectedUpdatedAt THEN NULL
                ELSE projectIdsWriteBlob
            END,
            projectIdsWriteUpdatedAt = CASE
                WHEN projectIdsWriteUpdatedAt <= :expectedUpdatedAt THEN 0
                ELSE projectIdsWriteUpdatedAt
            END,
            recordedByUserId = COALESCE(recordedByUserId, :recordedByUserId),
            recordedByDisplayName = COALESCE(recordedByDisplayName, :recordedByDisplayName),
            recordedByEmail = COALESCE(recordedByEmail, :recordedByEmail),
            syncStatus = CASE
                WHEN updatedAt = :expectedUpdatedAt THEN 'SYNCED'
                ELSE syncStatus
            END
        WHERE syncId = :syncId
          AND serverVersion = :expectedServerVersion
          AND :serverVersion >= serverVersion
    """)
    suspend fun acknowledgeSync(
        syncId: String,
        expectedUpdatedAt: Long,
        expectedServerVersion: Long,
        serverVersion: Long,
        projectIdsState: String,
        projectIdsBlob: String?,
        recordedByUserId: String?,
        recordedByDisplayName: String?,
        recordedByEmail: String?
    ): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM transactions
            WHERE syncStatus = 'SYNCED'
              AND projectIdsState = 'UNSPECIFIED'
            LIMIT 1
        )
        """
    )
    suspend fun hasSyncedTransactionsMissingProjectMetadata(): Boolean

    @Query(
        """
        UPDATE transactions
        SET recordedByUserId = :recordedByUserId,
            recordedByDisplayName = :recordedByDisplayName,
            recordedByEmail = :recordedByEmail
        WHERE syncId = :syncId
          AND syncStatus = 'SYNCED'
          AND recordedByUserId IS NULL
          AND recordedByDisplayName IS NULL
          AND recordedByEmail IS NULL
        """
    )
    suspend fun refreshRecordedByMetadata(
        syncId: String,
        recordedByUserId: String?,
        recordedByDisplayName: String?,
        recordedByEmail: String?
    ): Int

    @Query(
        """
        UPDATE transactions
        SET projectIdsState = :projectIdsState,
            projectIdsBlob = :projectIdsBlob
        WHERE syncId = :syncId
          AND syncStatus = 'SYNCED'
          AND projectIdsState = 'UNSPECIFIED'
        """
    )
    suspend fun refreshProjectMetadata(
        syncId: String,
        projectIdsState: String,
        projectIdsBlob: String?
    ): Int

    @Query("""
        UPDATE transactions
        SET serverVersion = :serverVersion
        WHERE syncId = :syncId
          AND serverVersion = :expectedServerVersion
          AND syncStatus != 'SYNCED'
          AND :serverVersion > serverVersion
    """)
    suspend fun rebasePendingSync(
        syncId: String,
        expectedServerVersion: Long,
        serverVersion: Long
    ): Int

    @androidx.room.Transaction
    suspend fun mergeRemote(transaction: TransactionEntity): Boolean {
        val existing = getBySyncId(transaction.syncId)
        if (existing != null && (existing.syncStatus != "SYNCED" || existing.serverVersion > transaction.serverVersion)) {
            return false
        }
        insert(
            transaction.copy(
                id = existing?.id ?: 0,
                syncStatus = "SYNCED",
                projectIdsWriteState = "UNSPECIFIED",
                projectIdsWriteBlob = null,
                projectIdsWriteUpdatedAt = 0,
                recordedByUserId = existing?.recordedByUserId ?: transaction.recordedByUserId,
                recordedByDisplayName = existing?.recordedByDisplayName ?: transaction.recordedByDisplayName,
                recordedByEmail = existing?.recordedByEmail ?: transaction.recordedByEmail
            )
        )
        return true
    }

    // === Search ===

    @Query("""
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND (note LIKE '%' || :keyword || '%'
           OR merchantName LIKE '%' || :keyword || '%')
        ORDER BY date DESC, createdAt DESC, syncId DESC
        LIMIT :limit
    """)
    suspend fun search(keyword: String, limit: Int = 50): List<TransactionEntity>
}

data class CategorySum(
    val categoryId: Long?,
    val total: Double
)
