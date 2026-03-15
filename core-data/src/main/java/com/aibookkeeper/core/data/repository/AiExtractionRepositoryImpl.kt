package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.model.ExtractionResult
import javax.inject.Inject

class AiExtractionRepositoryImpl @Inject constructor(
    private val strategyManager: ExtractionStrategyManager
) : AiExtractionRepository {

    override suspend fun extract(input: String, categoryNames: List<String>): Result<ExtractionResult> =
        strategyManager.extract(input, categoryNames)

    override suspend fun extractOnline(input: String, categoryNames: List<String>): Result<ExtractionResult> =
        strategyManager.extractOnline(input, categoryNames)

    override suspend fun extractFromOcr(ocrText: String, categoryNames: List<String>): Result<ExtractionResult> =
        strategyManager.extractFromOcr(ocrText, categoryNames)
}
