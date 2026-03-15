package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.mapper.CategoryMapper
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val mapper: CategoryMapper
) : CategoryRepository {

    override fun observeAllCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { entities -> entities.map(mapper::toDomain) }

    override fun observeExpenseCategories(): Flow<List<Category>> =
        categoryDao.observeTopLevelByType(TransactionType.EXPENSE.name)
            .map { entities -> entities.map(mapper::toDomain) }

    override fun observeIncomeCategories(): Flow<List<Category>> =
        categoryDao.observeTopLevelByType(TransactionType.INCOME.name)
            .map { entities -> entities.map(mapper::toDomain) }

    override fun observeSubCategories(parentId: Long): Flow<List<Category>> =
        categoryDao.observeSubCategories(parentId)
            .map { entities -> entities.map(mapper::toDomain) }

    override suspend fun getById(id: Long): Category? =
        categoryDao.getById(id)?.let(mapper::toDomain)

    override suspend fun findByNameAndType(name: String, type: TransactionType): Category? =
        categoryDao.findByNameAndType(name, type.name)?.let(mapper::toDomain)

    override suspend fun create(category: Category): Result<Long> = runCatching {
        categoryDao.insert(mapper.toEntity(category))
    }

    override suspend fun update(category: Category): Result<Unit> = runCatching {
        categoryDao.update(mapper.toEntity(category))
    }

    override suspend fun delete(id: Long): Result<Unit> = runCatching {
        val entity = categoryDao.getById(id) ?: throw IllegalArgumentException("Category not found")
        categoryDao.delete(entity)
    }
}
