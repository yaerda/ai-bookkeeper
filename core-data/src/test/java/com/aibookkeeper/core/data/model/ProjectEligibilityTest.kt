package com.aibookkeeper.core.data.model

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectEligibilityTest {
    private val binding = ProjectBinding(
        "project", "ledger", "旅行", true, "2026-09-08", "2026-09-09",
        "Asia/Shanghai", 1, false, true
    )

    @Test
    fun `Shanghai dates are inclusive and cached active is not authoritative`() {
        assertFalse(binding.isActiveAt(Instant.parse("2026-09-07T15:59:59Z")))
        assertTrue(binding.isActiveAt(Instant.parse("2026-09-07T16:00:00Z")))
        assertTrue(binding.isActiveAt(Instant.parse("2026-09-09T15:59:59Z")))
        assertFalse(binding.copy(active = true).isActiveAt(Instant.parse("2026-09-09T16:00:00Z")))
        assertFalse(binding.copy(enabled = false).isActiveAt(Instant.parse("2026-09-08T01:00:00Z")))
    }

    @Test
    fun `same complete cached snapshot reevaluates both midnight boundaries`() {
        val state = ProjectLedgerState(projects = listOf(binding), availability = ProjectDefaultsAvailability.CACHED)
        assertEquals(emptyList<String>(), state.defaultProjectIdsAt(Instant.parse("2026-09-07T15:59:59Z")))
        assertEquals(listOf("project"), state.defaultProjectIdsAt(Instant.parse("2026-09-07T16:00:00Z")))
        assertEquals(emptyList<String>(), state.defaultProjectIdsAt(Instant.parse("2026-09-09T16:00:00Z")))
        assertNull(state.copy(availability = ProjectDefaultsAvailability.UNAVAILABLE).defaultProjectIdsAt(Instant.EPOCH))
        assertEquals(emptyList<String>(), state.copy(projects = emptyList()).defaultProjectIdsAt(Instant.EPOCH))
    }
}
