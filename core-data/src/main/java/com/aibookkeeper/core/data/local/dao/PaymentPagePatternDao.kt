package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aibookkeeper.core.data.local.entity.PaymentPagePatternEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentPagePatternDao {

    @Insert
    suspend fun insert(pattern: PaymentPagePatternEntity): Long

    @Insert
    suspend fun insertAll(patterns: List<PaymentPagePatternEntity>)

    @Update
    suspend fun update(pattern: PaymentPagePatternEntity)

    @Query("DELETE FROM payment_page_patterns WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM payment_page_patterns WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PaymentPagePatternEntity?

    @Query("SELECT * FROM payment_page_patterns ORDER BY isSystem DESC, createdAt ASC")
    fun observeAll(): Flow<List<PaymentPagePatternEntity>>

    @Query("SELECT * FROM payment_page_patterns WHERE isEnabled = 1")
    suspend fun getEnabledPatterns(): List<PaymentPagePatternEntity>

    @Query("SELECT * FROM payment_page_patterns WHERE packageName = :packageName AND isEnabled = 1")
    suspend fun getEnabledByPackage(packageName: String): List<PaymentPagePatternEntity>

    @Query("SELECT DISTINCT packageName FROM payment_page_patterns WHERE isEnabled = 1")
    suspend fun getMonitoredPackages(): List<String>

    @Query("SELECT COUNT(*) FROM payment_page_patterns")
    suspend fun count(): Int
}
