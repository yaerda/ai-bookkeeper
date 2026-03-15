package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aibookkeeper.core.data.local.entity.MonthlyStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlyStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stats: MonthlyStatsEntity)

    @Query("SELECT * FROM monthly_stats WHERE month = :month")
    fun observeByMonth(month: String): Flow<MonthlyStatsEntity?>

    @Query("SELECT * FROM monthly_stats ORDER BY month DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<MonthlyStatsEntity>>
}
