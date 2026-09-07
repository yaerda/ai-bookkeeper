package com.aibookkeeper.feature.input.components

import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TransactionRecordedByUiTest {
    private val now = LocalDateTime.of(2026, 9, 7, 10, 0)

    @Test
    fun `summary prefers display name then email then neutral missing-member fallback for family ledgers`() {
        assertEquals(
            "Alice",
            transactionRecordedBySummary(transaction(displayName = " Alice "), showFamily = true)
        )
        assertEquals(
            "alice@example.test",
            transactionRecordedBySummary(transaction(email = "alice@example.test"), showFamily = true)
        )
        assertEquals(
            "成员信息未提供",
            transactionRecordedBySummary(transaction(userId = "user-1"), showFamily = true)
        )
    }

    @Test
    fun `summary keeps legacy rows explicitly unknown only in family mode`() {
        val legacy = transaction()
        assertNull(transactionRecordedBySummary(legacy, showFamily = false, showUnknown = true))
        assertEquals("未记录", transactionRecordedBySummary(legacy, showFamily = true, showUnknown = true))
        assertEquals("未记录 · 备注 · 9/7", joinTransactionMeta("未记录", "备注", "9/7"))
    }

    @Test
    fun `summary hides named labels outside family views`() {
        assertNull(
            transactionRecordedBySummary(
                transaction(displayName = "Alice", email = "alice@example.test"),
                showFamily = false,
                showUnknown = true
            )
        )
        assertNull(
            transactionRecordedBySummary(
                transaction(userId = "user-1"),
                showFamily = false,
                showUnknown = true
            )
        )
    }

    private fun transaction(
        userId: String? = null,
        displayName: String? = null,
        email: String? = null
    ) = Transaction(
        amount = 12.3,
        type = TransactionType.EXPENSE,
        categoryId = 1,
        categoryName = "餐饮",
        note = "备注",
        date = now,
        createdAt = now,
        updatedAt = now,
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.SYNCED,
        recordedByUserId = userId,
        recordedByDisplayName = displayName,
        recordedByEmail = email
    )
}
