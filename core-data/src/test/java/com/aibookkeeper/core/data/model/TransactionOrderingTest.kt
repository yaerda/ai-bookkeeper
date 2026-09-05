package com.aibookkeeper.core.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TransactionOrderingTest {
    @Test
    fun `chronology uses transaction time then creation time rather than edit time`() {
        val now = LocalDateTime.of(2026, 9, 5, 17, 30)
        fun transaction(id: String, date: LocalDateTime, created: LocalDateTime, updated: LocalDateTime = created) =
            Transaction(
                amount = 10.0, type = TransactionType.EXPENSE, categoryId = null,
                date = date, createdAt = created, updatedAt = updated,
                source = TransactionSource.MANUAL, status = TransactionStatus.CONFIRMED,
                syncStatus = SyncStatus.LOCAL, syncId = id
            )
        val records = listOf(
            transaction("old", now, now.minusMinutes(1), now.plusHours(1)),
            transaction("a", now, now),
            transaction("b", now, now),
            transaction("backdated", now.minusDays(1), now.plusMinutes(1)),
            transaction("latest", now.plusMinutes(1), now.plusMinutes(1))
        )
        assertEquals(
            listOf("latest", "b", "a", "old", "backdated"),
            records.sortedWith(newestTransactionFirst).map { it.syncId }
        )
    }
}
