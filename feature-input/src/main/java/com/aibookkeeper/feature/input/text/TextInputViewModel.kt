package com.aibookkeeper.feature.input.text

import com.aibookkeeper.core.common.util.CategoryIconMapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Category
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

sealed interface TextInputUiState {
    data object Idle : TextInputUiState
    data object Extracting : TextInputUiState
    data class Preview(
        val amount: Double,
        val category: String,
        val note: String?,
        val merchantName: String?,
        val date: String,
        val confidence: Float,
        val originalInput: String
    ) : TextInputUiState
    data object Saving : TextInputUiState
    data class Success(
        val transactionId: Long,
        val amount: Double,
        val category: String
    ) : TextInputUiState
    data class Error(val message: String) : TextInputUiState
}

@HiltViewModel
class TextInputViewModel @Inject constructor(
    private val aiExtractionRepository: AiExtractionRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<TextInputUiState>(TextInputUiState.Idle)
    val uiState: StateFlow<TextInputUiState> = _uiState.asStateFlow()

    val categories = categoryRepository.observeExpenseCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var lastExtractionResult: ExtractionResult? = null

    fun submitText(input: String) {
        if (input.isBlank()) {
            _uiState.value = TextInputUiState.Error("请输入记账内容")
            return
        }
        viewModelScope.launch {
            _uiState.value = TextInputUiState.Extracting
            aiExtractionRepository.extract(input)
                .onSuccess { result ->
                    lastExtractionResult = result
                    _uiState.value = TextInputUiState.Preview(
                        amount = result.amount ?: 0.0,
                        category = result.category,
                        note = result.note,
                        merchantName = result.merchantName,
                        date = result.date,
                        confidence = result.confidence,
                        originalInput = input
                    )
                }
                .onFailure { error ->
                    _uiState.value = TextInputUiState.Error(
                        error.message ?: "AI 提取失败，请重试"
                    )
                }
        }
    }

    fun confirmSave() {
        val preview = _uiState.value as? TextInputUiState.Preview ?: return
        val extraction = lastExtractionResult ?: return

        viewModelScope.launch {
            _uiState.value = TextInputUiState.Saving
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
                source = TransactionSource.TEXT_AI,
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
                    _uiState.value = TextInputUiState.Success(
                        transactionId = id,
                        amount = preview.amount,
                        category = extraction.category
                    )
                }
                .onFailure { error ->
                    _uiState.value = TextInputUiState.Error(
                        error.message ?: "保存失败"
                    )
                }
        }
    }

    fun saveManual(amount: Double, categoryId: Long?, categoryName: String, note: String?, type: TransactionType) {
        viewModelScope.launch {
            _uiState.value = TextInputUiState.Saving
            val now = LocalDateTime.now()
            val transaction = Transaction(
                amount = amount,
                type = type,
                categoryId = categoryId,
                categoryName = categoryName,
                note = note,
                date = now,
                createdAt = now,
                updatedAt = now,
                source = TransactionSource.MANUAL,
                status = TransactionStatus.CONFIRMED,
                syncStatus = SyncStatus.LOCAL
            )
            transactionRepository.create(transaction)
                .onSuccess { id ->
                    _uiState.value = TextInputUiState.Success(
                        transactionId = id,
                        amount = amount,
                        category = categoryName
                    )
                }
                .onFailure { error ->
                    _uiState.value = TextInputUiState.Error(
                        error.message ?: "保存失败"
                    )
                }
        }
    }

    fun resetToIdle() {
        _uiState.value = TextInputUiState.Idle
        lastExtractionResult = null
    }

    fun addCategory(name: String, icon: String = CategoryIconMapper.DEFAULT_ICON_KEY) {
        viewModelScope.launch {
            categoryRepository.create(
                Category(
                    name = name.trim(),
                    icon = normalizeCategoryIcon(icon),
                    color = "#607D8B",
                    type = TransactionType.EXPENSE,
                    isSystem = false
                )
            )
        }
    }

    fun updateCategory(category: Category, newName: String, newIcon: String) {
        viewModelScope.launch {
            categoryRepository.update(
                category.copy(
                    name = newName.trim(),
                    icon = normalizeCategoryIcon(newIcon)
                )
            )
        }
    }

    private fun normalizeCategoryIcon(icon: String): String =
        icon.trim().ifBlank { CategoryIconMapper.DEFAULT_ICON_KEY }
}
