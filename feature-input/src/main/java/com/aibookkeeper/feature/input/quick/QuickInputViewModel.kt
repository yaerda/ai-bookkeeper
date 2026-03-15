package com.aibookkeeper.feature.input.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * UI state for the quick input bottom sheet.
 */
sealed interface QuickInputUiState {
    /** Idle – waiting for user input. */
    data class Idle(
        val preselectedCategory: String? = null,
        val preselectedCategoryIcon: String? = null
    ) : QuickInputUiState

    /** AI extraction in progress. */
    data object Extracting : QuickInputUiState

    /** AI extraction completed – show preview for confirmation. */
    data class Preview(
        val amount: Double,
        val category: String,
        val note: String?,
        val confidence: Float,
        val originalInput: String
    ) : QuickInputUiState

    /** Saving the transaction. */
    data object Saving : QuickInputUiState

    /** Successfully saved. */
    data class Success(
        val transactionId: Long,
        val amount: Double,
        val category: String
    ) : QuickInputUiState

    /** Error occurred. */
    data class Error(val message: String) : QuickInputUiState
}

@HiltViewModel
class QuickInputViewModel @Inject constructor(
    private val aiExtractionRepository: AiExtractionRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<QuickInputUiState>(QuickInputUiState.Idle())
    val uiState: StateFlow<QuickInputUiState> = _uiState.asStateFlow()

    private var lastExtractionResult: ExtractionResult? = null

    /**
     * Set preselected category (from notification category button).
     */
    fun setPreselectedCategory(name: String?, icon: String?) {
        _uiState.value = QuickInputUiState.Idle(
            preselectedCategory = name,
            preselectedCategoryIcon = icon
        )
    }

    /**
     * Submit natural-language text for AI extraction.
     */
    fun submitText(input: String) {
        if (input.isBlank()) {
            _uiState.value = QuickInputUiState.Error("请输入记账内容")
            return
        }

        viewModelScope.launch {
            _uiState.value = QuickInputUiState.Extracting
            aiExtractionRepository.extract(input)
                .onSuccess { result ->
                    lastExtractionResult = result
                    _uiState.value = QuickInputUiState.Preview(
                        amount = result.amount ?: 0.0,
                        category = result.category,
                        note = result.note,
                        confidence = result.confidence,
                        originalInput = input
                    )
                }
                .onFailure { error ->
                    _uiState.value = QuickInputUiState.Error(
                        error.message ?: "AI 提取失败，请重试"
                    )
                }
        }
    }

    /**
     * Submit a quick-category entry: only amount is needed, category is preselected.
     */
    fun submitCategoryAmount(amount: Double, categoryName: String) {
        viewModelScope.launch {
            _uiState.value = QuickInputUiState.Saving
            val now = LocalDateTime.now()
            val category = categoryRepository.findByNameAndType(categoryName, TransactionType.EXPENSE)
            val transaction = Transaction(
                amount = amount,
                type = TransactionType.EXPENSE,
                categoryId = category?.id,
                categoryName = categoryName,
                date = now,
                createdAt = now,
                updatedAt = now,
                source = TransactionSource.NOTIFICATION_QUICK,
                status = TransactionStatus.CONFIRMED,
                syncStatus = SyncStatus.LOCAL,
                originalInput = "快捷分类: $categoryName ¥${"%.2f".format(amount)}"
            )
            transactionRepository.create(transaction)
                .onSuccess { id ->
                    _uiState.value = QuickInputUiState.Success(
                        transactionId = id,
                        amount = amount,
                        category = categoryName
                    )
                }
                .onFailure { error ->
                    _uiState.value = QuickInputUiState.Error(
                        error.message ?: "保存失败"
                    )
                }
        }
    }

    /**
     * Confirm the previewed extraction and save the transaction.
     */
    fun confirmSave() {
        val preview = _uiState.value as? QuickInputUiState.Preview ?: return
        val extraction = lastExtractionResult ?: return

        viewModelScope.launch {
            _uiState.value = QuickInputUiState.Saving
            val now = LocalDateTime.now()
            val txType = when (extraction.type.uppercase()) {
                "INCOME" -> TransactionType.INCOME
                else -> TransactionType.EXPENSE
            }
            val category = categoryRepository.findByNameAndType(
                extraction.category, txType
            )
            val txDate = try {
                LocalDate.parse(extraction.date).atStartOfDay()
            } catch (_: Exception) {
                now
            }

            val transaction = Transaction(
                amount = preview.amount,
                type = txType,
                categoryId = category?.id,
                categoryName = extraction.category,
                merchantName = extraction.merchantName,
                note = extraction.note,
                originalInput = preview.originalInput,
                date = txDate,
                createdAt = now,
                updatedAt = now,
                source = TransactionSource.NOTIFICATION_QUICK,
                status = if (extraction.confidence >= 0.7f) {
                    TransactionStatus.CONFIRMED
                } else {
                    TransactionStatus.PENDING
                },
                syncStatus = SyncStatus.LOCAL,
                aiConfidence = extraction.confidence
            )

            transactionRepository.create(transaction)
                .onSuccess { id ->
                    _uiState.value = QuickInputUiState.Success(
                        transactionId = id,
                        amount = preview.amount,
                        category = extraction.category
                    )
                }
                .onFailure { error ->
                    _uiState.value = QuickInputUiState.Error(
                        error.message ?: "保存失败"
                    )
                }
        }
    }

    /**
     * Reset to idle state for retry.
     */
    fun resetToIdle() {
        _uiState.value = QuickInputUiState.Idle()
        lastExtractionResult = null
    }
}
