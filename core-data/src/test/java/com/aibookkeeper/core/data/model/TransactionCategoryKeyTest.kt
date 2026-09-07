package com.aibookkeeper.core.data.model

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TransactionCategoryKeyTest {
    private fun transaction(name: String?, id: Long? = null): Transaction {
        val now = LocalDateTime.of(2026, 9, 1, 12, 0)
        return Transaction(
            amount = 1.0, type = TransactionType.EXPENSE,
            categoryId = id, categoryName = name, date = now,
            createdAt = now, updatedAt = now, source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED, syncStatus = SyncStatus.LOCAL
        )
    }

    @Test
    fun `uses catalog identity before deterministic shared-category fallback`() {
        assertEquals(7L, transaction("Alpha", 7).categoryKey())
        assertEquals(-"Alpha".hashCode().toLong(), transaction("Alpha").categoryKey())
        assertNotEquals(transaction("Alpha").categoryKey(), transaction("Beta").categoryKey())
        assertEquals(0L, transaction(null).categoryKey())
    }
}
