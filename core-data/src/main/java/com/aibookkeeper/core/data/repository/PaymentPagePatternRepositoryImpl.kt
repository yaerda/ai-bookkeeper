package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.local.dao.PaymentPagePatternDao
import com.aibookkeeper.core.data.mapper.toDomain
import com.aibookkeeper.core.data.mapper.toEntity
import com.aibookkeeper.core.data.model.PaymentPagePattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PaymentPagePatternRepositoryImpl @Inject constructor(
    private val paymentPagePatternDao: PaymentPagePatternDao
) : PaymentPagePatternRepository {

    override fun observeAll(): Flow<List<PaymentPagePattern>> =
        paymentPagePatternDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getEnabledPatterns(): List<PaymentPagePattern> =
        paymentPagePatternDao.getEnabledPatterns().map { it.toDomain() }

    override suspend fun getEnabledByPackage(packageName: String): List<PaymentPagePattern> =
        paymentPagePatternDao.getEnabledByPackage(packageName).map { it.toDomain() }

    override suspend fun getMonitoredPackages(): Set<String> =
        paymentPagePatternDao.getMonitoredPackages().toSet()

    override suspend fun create(pattern: PaymentPagePattern): Result<Long> = runCatching {
        paymentPagePatternDao.insert(pattern.toEntity())
    }

    override suspend fun update(pattern: PaymentPagePattern): Result<Unit> = runCatching {
        val existing = paymentPagePatternDao.getById(pattern.id)
            ?: throw IllegalArgumentException("Payment page pattern not found")
        paymentPagePatternDao.update(pattern.toEntity(createdAt = existing.createdAt))
    }

    override suspend fun delete(id: Long): Result<Unit> = runCatching {
        paymentPagePatternDao.deleteById(id)
    }
}
