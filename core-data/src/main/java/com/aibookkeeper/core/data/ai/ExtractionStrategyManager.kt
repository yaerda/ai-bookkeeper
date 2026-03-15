package com.aibookkeeper.core.data.ai

import android.util.Log
import com.aibookkeeper.core.common.constants.AppConstants
import com.aibookkeeper.core.data.model.ExtractionResult
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Manages extraction strategy selection (online vs offline).
 * Tries Azure OpenAI first; falls back to local regex rules
 * when network is unavailable, AI times out, or config is missing.
 */
class ExtractionStrategyManager @Inject constructor(
    private val localExtractor: LocalRuleExtractor,
    private val onlineExtractor: AzureOpenAiExtractor
) {
    companion object {
        private const val TAG = "ExtractionStrategy"
    }

    suspend fun extract(input: String): Result<ExtractionResult> {
        return tryOnlineThenFallback { onlineExtractor.extract(input) }
            ?: localExtractor.extract(input)
    }

    suspend fun extractOnline(input: String): Result<ExtractionResult> {
        return onlineExtractor.extract(input)
    }

    suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult> {
        return tryOnlineThenFallback { onlineExtractor.extractFromOcr(ocrText) }
            ?: localExtractor.extractFromOcr(ocrText)
    }

    /**
     * Attempts online extraction with a timeout.
     * Returns null if online fails so caller can fall back.
     */
    private suspend fun tryOnlineThenFallback(
        block: suspend () -> Result<ExtractionResult>
    ): Result<ExtractionResult>? {
        return try {
            val result = withTimeoutOrNull(AppConstants.AI_TIMEOUT_MS) { block() }
            if (result != null && result.isSuccess) result else {
                Log.w(TAG, "Online extraction failed or timed out, falling back to local")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Online extraction error, falling back to local", e)
            null
        }
    }
}
