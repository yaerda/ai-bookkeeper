package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
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

    // === Update ===

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("UPDATE transactions SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE transactions SET syncStatus = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, syncStatus: String)

    // === Delete ===

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // === Query - Single ===

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: Long): Flow<TransactionEntity?>

    // === Query - List (reactive) ===

    @Query("""
        SELECT * FROM transactions 
        WHERE date BETWEEN :startMillis AND :endMillis 
        ORDER BY date DESC
    """)
    fun observeByDateRange(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions 
        WHERE date BETWEEN :startMillis AND :endMillis AND type = :type
        ORDER BY date DESC
    """)
    fun observeByDateRangeAndType(
        startMillis: Long, endMillis: Long, type: String
    ): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions 
        WHERE status = :status 
        ORDER BY createdAt DESC
    """)
    fun observeByStatus(status: String): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions 
        WHERE categoryId = :categoryId AND date BETWEEN :startMillis AND :endMillis
        ORDER BY date DESC
    """)
    fun observeByCategoryAndDateRange(
        categoryId: Long, startMillis: Long, endMillis: Long
    ): Flow<List<TransactionEntity>>

    // === Aggregate queries ===

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions 
        WHERE type = :type AND date BETWEEN :startMillis AND :endMillis
    """)
    suspend fun sumByTypeAndDateRange(type: String, startMillis: Long, endMillis: Long): Double

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions 
        WHERE type = :type AND date BETWEEN :startMillis AND :endMillis
    """)
    fun observeSumByTypeAndDateRange(
        type: String, startMillis: Long, endMillis: Long
    ): Flow<Double>

    @Query("""
        SELECT categoryId, SUM(amount) as total 
        FROM transactions 
        WHERE type = 'EXPENSE' AND date BETWEEN :startMillis AND :endMillis
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    fun observeExpenseBreakdown(
        startMillis: Long, endMillis: Long
    ): Flow<List<CategorySum>>

    @Query("""
        SELECT COUNT(*) FROM transactions 
        WHERE date BETWEEN :startMillis AND :endMillis
    """)
    suspend fun countByDateRange(startMillis: Long, endMillis: Long): Int

    // === Sync ===

    @Query("SELECT * FROM transactions WHERE syncStatus = 'PENDING_SYNC' ORDER BY updatedAt ASC")
    suspend fun getPendingSyncTransactions(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncStatus = 'PENDING_SYNC'")
    fun observePendingSyncCount(): Flow<Int>

    // === Search ===

    @Query("""
        SELECT * FROM transactions 
        WHERE note LIKE '%' || :keyword || '%' 
           OR merchantName LIKE '%' || :keyword || '%'
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun search(keyword: String, limit: Int = 50): List<TransactionEntity>
}

data class CategorySum(
    val categoryId: Long?,
    val total: Double
)
