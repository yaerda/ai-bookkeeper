package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.model.ExtractionResult

interface AiExtractionRepository {
    suspend fun extract(input: String, categoryNames: List<String> = emptyList()): Result<ExtractionResult>
    suspend fun extractOnline(input: String, categoryNames: List<String> = emptyList()): Result<ExtractionResult>
    suspend fun extractFromOcr(ocrText: String, categoryNames: List<String> = emptyList()): Result<ExtractionResult>
}
