package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.ai.ActiveExtractionCategoryProvider
import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.model.ExtractionResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class AiExtractionRepositoryImpl @Inject constructor(
    private val strategyManager: ExtractionStrategyManager,
    private val extractionCategoryProvider: ActiveExtractionCategoryProvider
) : AiExtractionRepository {

    override suspend fun extract(input: String, categoryNames: List<String>): Result<ExtractionResult> {
        return withCategories(categoryNames) { strategyManager.extract(input, it) }
    }

    override suspend fun extractOnline(input: String, categoryNames: List<String>): Result<ExtractionResult> {
        return withCategories(categoryNames) { strategyManager.extractOnline(input, it) }
    }

    override suspend fun extractFromOcr(ocrText: String, categoryNames: List<String>): Result<ExtractionResult> {
        return withCategories(categoryNames) { strategyManager.extractFromOcr(ocrText, it) }
    }

    private suspend fun withCategories(
        names: List<String>,
        extract: suspend (List<String>) -> Result<ExtractionResult>
    ): Result<ExtractionResult> = try {
        extract(extractionCategoryProvider.getCategoryNames(names))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
