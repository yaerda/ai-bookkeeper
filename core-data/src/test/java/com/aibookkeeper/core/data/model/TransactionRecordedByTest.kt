package com.aibookkeeper.core.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TransactionRecordedByTest {
    @Test
    fun `recorded-by label prefers name then email then neutral missing-member fallback`() {
        assertEquals("Cloud Name", transactionRecordedByLabel(" Cloud Name ", "user@example.test", "user-1"))
        assertEquals("user@example.test", transactionRecordedByLabel(null, "user@example.test", "user-1"))
        assertEquals("成员信息未提供", transactionRecordedByLabel(null, null, "user-1"))
    }

    @Test
    fun `legacy rows remain unknown when no provenance exists`() {
        assertNull(transactionRecordedByLabel(null, null, null))
        assertNull(transactionRecordedByLabel("  ", " ", " "))
    }
}
