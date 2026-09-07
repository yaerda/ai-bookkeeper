package com.aibookkeeper.feature.sync.network

import com.aibookkeeper.core.data.repository.LedgerOption
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FamilyIdentityLabelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `cloud owner and invitation labels preserve email identity`() {
        val result = json.decodeFromString<FamilyLedgersResponse>(
            """{"ledgers":[{"id":"shared","name":"Family","ownerEmail":"owner@example.test","ownerDisplayName":"Owner Name","role":"VIEWER","mode":"FAMILY"}],"invitations":[{"id":"invite","ledgerId":"shared","ledgerName":"Family","inviterEmail":"owner@example.test","inviterDisplayName":"Owner Name","role":"EDITOR"}]}"""
        )
        assertEquals("Owner Name", result.ledgers.single().ownerLabel)
        assertEquals("owner@example.test", result.ledgers.single().ownerEmail)
        assertEquals("Owner Name", result.invitations.single().inviterLabel)
        assertEquals("owner@example.test", result.invitations.single().inviterEmail)
    }

    @Test
    fun `old servers and explicit cleared names retain email fallback`() {
        val legacy = json.decodeFromString<FamilyLedgerDto>(
            """{"id":"shared","name":"Family","ownerEmail":"owner@example.test","role":"EDITOR","mode":"FAMILY"}"""
        )
        assertNull(legacy.ownerDisplayName)
        assertEquals("owner@example.test", legacy.ownerLabel)
        assertEquals("owner@example.test", legacy.copy(ownerDisplayName = "").ownerLabel)
        assertEquals("owner@example.test", legacy.copy(ownerDisplayName = "  ").ownerLabel)
        val option = LedgerOption("shared", "Family", "owner@example.test", "VIEWER", "FAMILY", false, " Cloud owner ")
        assertEquals("Cloud owner", option.ownerLabel)
        assertEquals("owner@example.test", option.copy(ownerDisplayName = null).ownerLabel)
    }

    @Test
    fun `member names follow the same display-only fallback`() {
        val member = json.decodeFromString<FamilyMemberDto>(
            """{"id":"member","userId":"user","email":"member@example.test","role":"VIEWER","displayName":"Member Name"}"""
        )
        assertEquals("Member Name", member.displayLabel)
        assertEquals("member@example.test", member.email)
        assertEquals("member@example.test", member.copy(displayName = null).displayLabel)
    }

    @Test
    fun `transaction author labels distinguish known, former and unknown legacy rows`() {
        val named = json.decodeFromString<SyncTransactionDto>(
            """{"syncId":"0ec11d58-589d-40c5-bc30-e4524b539a2c","serverVersion":2,"amount":12.5,"type":"EXPENSE","categoryId":1,"categoryName":"餐饮","categoryIcon":null,"categoryColor":null,"merchantName":null,"note":null,"originalInput":null,"date":1,"createdAt":1,"updatedAt":1,"source":"MANUAL","status":"CONFIRMED","aiConfidence":null,"deletedAt":null,"recordedByUserId":"user-1","recordedByDisplayName":"Cloud Name","recordedByEmail":"user@example.test"}"""
        )
        assertEquals("Cloud Name", named.recordedByLabel)
        assertEquals("user@example.test", named.copy(recordedByDisplayName = null).recordedByLabel)
        assertEquals("成员信息未提供", named.copy(recordedByDisplayName = null, recordedByEmail = null).recordedByLabel)
        val legacy = named.copy(recordedByUserId = null, recordedByDisplayName = null, recordedByEmail = null)
        assertNull(legacy.recordedByLabel)
    }
}
