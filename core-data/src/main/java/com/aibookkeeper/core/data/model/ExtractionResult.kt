package com.aibookkeeper.core.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class ExtractionResult(
    val amount: Double?,
    val type: String,
    val category: String,
    val merchantName: String? = null,
    val date: String,         // ISO date string, parsed by caller
    val note: String? = null,
    val confidence: Float,
    val source: ExtractionSource = ExtractionSource.AZURE_AI
)

enum class ExtractionSource {
    AZURE_AI,
    LOCAL_RULE
}
