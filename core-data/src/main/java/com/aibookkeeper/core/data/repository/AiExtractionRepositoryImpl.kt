package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.ai.ExtractionCategoryProvider
import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.model.ExtractionResult
import javax.inject.Inject

class AiExtractionRepositoryImpl @Inject constructor(
    private val strategyManager: ExtractionStrategyManager,
    private val extractionCategoryProvider: ExtractionCategoryProvider
) : AiExtractionRepository {

    override suspend fun extract(input: String, categoryNames: List<String>): Result<ExtractionResult> {
        return strategyManager.extract(
            input,
            extractionCategoryProvider.getCategoryNames(categoryNames)
        )
    }

    override suspend fun extractOnline(input: String, categoryNames: List<String>): Result<ExtractionResult> {
        return strategyManager.extractOnline(
            input,
            extractionCategoryProvider.getCategoryNames(categoryNames)
        )
    }

    override suspend fun extractFromOcr(ocrText: String, categoryNames: List<String>): Result<ExtractionResult> {
        return strategyManager.extractFromOcr(
            ocrText,
            extractionCategoryProvider.getCategoryNames(categoryNames)
        )
    }
}
