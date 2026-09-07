package com.aibookkeeper.feature.input.quick

import com.aibookkeeper.core.common.extensions.resolveTransactionDate

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
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerSelection
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.requireEditable
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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
        val date: String,
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
    private val categoryRepository: CategoryRepository,
    private val ledgerContext: LedgerContext,
    projectRepository: ProjectRepository
) : ViewModel() {

    val projectState = projectRepository.currentLedgerState
    val ledgerState = ledgerContext.state
    private val _uiState = MutableStateFlow<QuickInputUiState>(QuickInputUiState.Idle())
    val uiState: StateFlow<QuickInputUiState> = _uiState.asStateFlow()

    private var lastExtractionResult: ExtractionResult? = null
    private var preselectedCategory: String? = null
    private var preselectedCategoryIcon: String? = null
    private var preselectedSelection: LedgerSelection? = null
    private var extractionSelection: LedgerSelection? = null

    /**
     * Set preselected category (from notification category button).
     */
    fun setPreselectedCategory(name: String?, icon: String?) {
        preselectedCategory = name
        preselectedCategoryIcon = icon
        preselectedSelection = ledgerContext.state.value.selection
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

        val selection = ledgerContext.state.value.selection
        viewModelScope.launch {
            _uiState.value = QuickInputUiState.Extracting
            runCatching {
                ledgerContext.requireEditable(selection)
                val result = aiExtractionRepository.extract(input).getOrThrow()
                ledgerContext.requireEditable(selection)
                result
            }
                .onSuccess { result ->
                    lastExtractionResult = result
                    extractionSelection = selection
                    _uiState.value = QuickInputUiState.Preview(
                        amount = result.amount ?: 0.0,
                        category = result.category,
                        note = result.note,
                        date = result.date,
                        confidence = result.confidence,
                        originalInput = input
                    )
                }
                .onFailure { error ->
                    showError(error, "AI 提取失败，请重试")
                }
        }
    }

    /**
     * Submit a quick-category entry: only amount is needed, category is preselected.
     */
    fun submitCategoryAmount(amount: Double, categoryName: String, projectIds: List<String>? = null) {
        if (_uiState.value !is QuickInputUiState.Idle) return
        _uiState.value = QuickInputUiState.Saving
        val selection = preselectedSelection ?: ledgerContext.state.value.selection
        val selectedProjectIds = projectIds?.toList()

        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(selection)
                val now = LocalDateTime.now()
                val category = categoryRepository.findByNameAndType(categoryName, TransactionType.EXPENSE)
                val transaction = Transaction(
                    amount = amount,
                    type = TransactionType.EXPENSE,
                    categoryId = category?.id,
                    categoryName = category?.name ?: categoryName,
                    categoryIcon = category?.icon,
                    categoryColor = category?.color,
                    date = now,
                    createdAt = now,
                    updatedAt = now,
                    source = TransactionSource.NOTIFICATION_QUICK,
                    status = TransactionStatus.CONFIRMED,
                    syncStatus = SyncStatus.LOCAL,
                    originalInput = "快捷分类: $categoryName ¥${"%.2f".format(amount)}",
                    projectIds = selectedProjectIds
                )
                ledgerContext.requireEditable(selection)
                transactionRepository.create(transaction).getOrThrow()
            }
                .onSuccess { id ->
                    _uiState.value = QuickInputUiState.Success(
                        transactionId = id,
                        amount = amount,
                        category = categoryName
                    )
                }
                .onFailure { error ->
                    showError(error, "保存失败")
                }
        }
    }

    /**
     * Confirm the previewed extraction and save the transaction.
     */
    fun confirmSave(projectIds: List<String>? = null) {
        val preview = _uiState.value as? QuickInputUiState.Preview ?: return
        val extraction = lastExtractionResult ?: return
        val selection = extractionSelection ?: return
        val selectedProjectIds = projectIds?.toList()
        _uiState.value = QuickInputUiState.Saving

        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(selection)
                val now = LocalDateTime.now()
                val txType = when (extraction.type.uppercase()) {
                    "INCOME" -> TransactionType.INCOME
                    else -> TransactionType.EXPENSE
                }
                val category = categoryRepository.findByNameAndType(extraction.category, txType)
                val txDate = resolveTransactionDate(extraction.date, now)
                val transaction = Transaction(
                    amount = preview.amount,
                    type = txType,
                    categoryId = category?.id,
                    categoryName = category?.name ?: extraction.category,
                    categoryIcon = category?.icon,
                    categoryColor = category?.color,
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
                    aiConfidence = extraction.confidence,
                    projectIds = selectedProjectIds
                )
                ledgerContext.requireEditable(selection)
                transactionRepository.create(transaction).getOrThrow()
            }
                .onSuccess { id ->
                    _uiState.value = QuickInputUiState.Success(
                        transactionId = id,
                        amount = preview.amount,
                        category = extraction.category
                    )
                }
                .onFailure { error ->
                    showError(error, "保存失败")
                }
        }
    }

    /**
     * Reset to idle state for retry.
     */
    fun resetToIdle() {
        preselectedCategory = null
        preselectedCategoryIcon = null
        preselectedSelection = null
        _uiState.value = QuickInputUiState.Idle()
        lastExtractionResult = null
        extractionSelection = null
    }

    fun resetAfterSave() {
        _uiState.value = QuickInputUiState.Idle(
            preselectedCategory = preselectedCategory,
            preselectedCategoryIcon = preselectedCategoryIcon
        )
        lastExtractionResult = null
        extractionSelection = null
    }

    private fun showError(error: Throwable, fallback: String) {
        if (error is CancellationException) throw error
        _uiState.value = QuickInputUiState.Error(error.message ?: fallback)
    }
}
