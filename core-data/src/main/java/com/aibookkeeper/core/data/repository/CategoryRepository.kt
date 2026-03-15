package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {

    fun observeAllCategories(): Flow<List<Category>>

    fun observeExpenseCategories(): Flow<List<Category>>

    fun observeIncomeCategories(): Flow<List<Category>>

    fun observeSubCategories(parentId: Long): Flow<List<Category>>

    suspend fun getById(id: Long): Category?

    suspend fun findByNameAndType(name: String, type: TransactionType): Category?

    suspend fun create(category: Category): Result<Long>

    suspend fun update(category: Category): Result<Unit>

    suspend fun delete(id: Long): Result<Unit>
}
