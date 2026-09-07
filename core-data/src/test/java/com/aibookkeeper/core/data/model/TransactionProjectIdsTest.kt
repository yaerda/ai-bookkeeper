package com.aibookkeeper.core.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TransactionProjectIdsTest {
    @Test
    fun `encode and decode preserve null explicit empty and multiple project ids`() {
        assertEquals("UNSPECIFIED" to null, encodeProjectIds(null))
        assertNull(decodeProjectIds("UNSPECIFIED", null))

        val explicitEmpty = encodeProjectIds(emptyList())
        assertEquals("EXPLICIT", explicitEmpty.first)
        assertEquals("", explicitEmpty.second)
        assertEquals(emptyList<String>(), decodeProjectIds(explicitEmpty.first, explicitEmpty.second))

        val explicit = encodeProjectIds(listOf("project-a", "project-b"))
        assertEquals(
            listOf("project-a", "project-b"),
            decodeProjectIds(explicit.first, explicit.second)
        )
    }
}
