package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionResult
import javax.inject.Inject

/**
 * Manages extraction strategy selection (online vs offline).
 * Falls back to local rules when network is unavailable or AI times out.
 */
class ExtractionStrategyManager @Inject constructor(
    private val localExtractor: LocalRuleExtractor
    // TODO: Inject AzureOpenAiExtractor when implemented by backend engineer
) {

    suspend fun extract(input: String): Result<ExtractionResult> {
        // TODO: Try online first, fallback to local
        // For v1.0 skeleton, use local extractor
        return localExtractor.extract(input)
    }

    suspend fun extractOnline(input: String): Result<ExtractionResult> {
        // TODO: Force online extraction via AzureOpenAiExtractor
        return localExtractor.extract(input)
    }

    suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult> {
        // TODO: Use OCR-specific prompt for online, fallback to local
        return localExtractor.extractFromOcr(ocrText)
    }
}
