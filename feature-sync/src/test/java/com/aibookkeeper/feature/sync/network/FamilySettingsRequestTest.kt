package com.aibookkeeper.feature.sync.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FamilySettingsRequestTest {

    @Test
    fun `conversion request does not send a null name`() {
        val json = Json { encodeDefaults = true }

        assertEquals(
            """{"mode":"FAMILY"}""",
            json.encodeToString(FamilySettingsRequest(mode = "FAMILY"))
        )
    }

    @Test
    fun `new ledger request includes its name and mode`() {
        val json = Json { encodeDefaults = true }

        assertEquals(
            """{"name":"旅行基金","mode":"PERSONAL"}""",
            json.encodeToString(
                CreateLedgerRequest(name = "旅行基金", mode = "PERSONAL")
            )
        )
    }
}
