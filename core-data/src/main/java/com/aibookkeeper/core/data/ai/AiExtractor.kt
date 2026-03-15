package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionResult

interface AiExtractor {
    suspend fun extract(input: String, categoryNames: List<String> = emptyList()): Result<ExtractionResult>
    suspend fun extractFromOcr(ocrText: String, categoryNames: List<String> = emptyList()): Result<ExtractionResult>
}
