package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.model.PaymentPagePattern
import kotlinx.coroutines.flow.Flow

interface PaymentPagePatternRepository {

    fun observeAll(): Flow<List<PaymentPagePattern>>

    suspend fun getEnabledPatterns(): List<PaymentPagePattern>

    suspend fun getEnabledByPackage(packageName: String): List<PaymentPagePattern>

    suspend fun getMonitoredPackages(): Set<String>

    suspend fun create(pattern: PaymentPagePattern): Result<Long>

    suspend fun update(pattern: PaymentPagePattern): Result<Unit>

    suspend fun delete(id: Long): Result<Unit>
}
