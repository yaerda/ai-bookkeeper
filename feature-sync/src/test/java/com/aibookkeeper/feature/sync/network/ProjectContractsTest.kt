package com.aibookkeeper.feature.sync.network

import com.aibookkeeper.feature.sync.queue.toDomainTransaction
import com.aibookkeeper.feature.sync.queue.toSyncDto
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ProjectContractsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `sync transaction json preserves null and explicit empty project ids`() {
        val base = SyncTransactionDto(
            syncId = "0ec11d58-589d-40c5-bc30-e4524b539a2c",
            serverVersion = 1,
            amount = 12.5,
            type = "EXPENSE",
            categoryId = 1,
            categoryName = "餐饮",
            categoryIcon = null,
            categoryColor = null,
            merchantName = null,
            note = null,
            originalInput = null,
            date = 1,
            createdAt = 1,
            updatedAt = 1,
            source = "MANUAL",
            status = "CONFIRMED",
            aiConfidence = null,
            deletedAt = null
        )

        assertTrue(json.encodeToString(base).contains("\"projectIds\":null"))
        assertTrue(json.encodeToString(base.copy(projectIds = emptyList())).contains("\"projectIds\":[]"))
    }

    @Test
    fun `domain mapping keeps null for outbound defaults and empty list for inbound authoritative no tags`() {
        val transaction = Transaction(
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            categoryName = "餐饮",
            date = LocalDateTime.of(2026, 9, 8, 10, 0),
            createdAt = LocalDateTime.of(2026, 9, 8, 10, 0),
            updatedAt = LocalDateTime.of(2026, 9, 8, 10, 0),
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            syncStatus = SyncStatus.LOCAL,
            projectIds = null
        )

        assertEquals(null, transaction.toSyncDto().projectIds)
        assertEquals(null, transaction.toSyncDto().toDomainTransaction().projectIds)
        assertEquals(
            emptyList<String>(),
            transaction.toSyncDto().copy(projectIds = emptyList()).toDomainTransaction().projectIds
        )
    }
}
