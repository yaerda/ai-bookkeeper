package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.normalizeCategoryName
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE type = :type AND parentId IS NULL ORDER BY sortOrder ASC")
    fun observeTopLevelByType(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY sortOrder ASC")
    fun observeSubCategories(parentId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY type, sortOrder ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY type, sortOrder ASC")
    suspend fun getAllOnce(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByNameAndType(name: String, type: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Transaction
    suspend fun mergeCloudCatalog(categories: List<CategoryEntity>) {
        val existing = getAllOnce().toMutableList()
        categories.forEach { incoming ->
            val name = normalizeCategoryName(incoming.name)
            val matches = existing.filter {
                it.type == incoming.type &&
                    normalizeCategoryName(it.name).equals(name, ignoreCase = true)
            }
            if (matches.isEmpty()) {
                val entity = incoming.copy(id = 0, name = name, parentId = null)
                val id = insert(entity)
                existing += entity.copy(id = id)
            } else {
                // Keep every local ID (and its historical transaction references).
                matches.forEach { local ->
                    val merged = incoming.copy(id = local.id, name = name, parentId = local.parentId)
                    update(merged)
                    existing[existing.indexOf(local)] = merged
                }
            }
        }
    }

    @Transaction
    suspend fun resolveRemoteCategory(
        name: String,
        type: String,
        icon: String?,
        color: String?
    ): Long {
        val normalizedName = normalizeCategoryName(name)
        val existing = findByNameAndType(normalizedName, type)
            ?: getAllOnce().firstOrNull {
                it.type == type &&
                    normalizeCategoryName(it.name).equals(normalizedName, ignoreCase = true)
            }
        return existing?.id ?: insert(
            CategoryEntity(
                name = normalizedName,
                type = type,
                icon = icon?.trim()?.takeIf { it.isNotBlank() }
                    ?: if (type == "INCOME") "ic_other_income" else "ic_other",
                color = color?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#607D8B",
                isSystem = false,
                sortOrder = 1000
            )
        )
    }
}
