package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.model.ExtractionResult
import javax.inject.Inject

class AiExtractionRepositoryImpl @Inject constructor(
    private val strategyManager: ExtractionStrategyManager
) : AiExtractionRepository {

    override suspend fun extract(input: String): Result<ExtractionResult> =
        strategyManager.extract(input)

    override suspend fun extractOnline(input: String): Result<ExtractionResult> =
        strategyManager.extractOnline(input)

    override suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult> =
        strategyManager.extractFromOcr(ocrText)
}
