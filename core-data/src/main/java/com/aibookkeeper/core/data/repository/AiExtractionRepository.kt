package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.model.ExtractionResult

interface AiExtractionRepository {

    suspend fun extract(input: String): Result<ExtractionResult>

    suspend fun extractOnline(input: String): Result<ExtractionResult>

    suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult>
}
