package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.data.model.ExtractionResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureScreenTest {

    @Test
    fun `should default to split for mixed income and expense items`() {
        val items = listOf(
            item(type = "EXPENSE"),
            item(type = "income")
        )

        assertTrue(shouldDefaultToSplit(items))
    }

    @Test
    fun `should keep single mode for items with the same type`() {
        val items = listOf(
            item(type = "EXPENSE"),
            item(type = "expense")
        )

        assertFalse(shouldDefaultToSplit(items))
    }

    private fun item(type: String) = ExtractionResult(
        amount = 10.0,
        type = type,
        category = "其他",
        date = "2026-08-23",
        confidence = 0.9f
    )
}
