package com.aibookkeeper.feature.input.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.normalizeCategoryName
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.requireEditable
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Loaded(val transaction: Transaction) : DetailUiState
    data object NotFound : DetailUiState
    data object Deleted : DetailUiState
}

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: com.aibookkeeper.core.data.repository.CategoryRepository,
    private val ledgerContext: LedgerContext
) : ViewModel() {

    private val transactionId: Long = savedStateHandle["transactionId"] ?: -1L
    val ledgerState = ledgerContext.state
    private val transactionSelection = ledgerState.value.selection
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    val uiState: StateFlow<DetailUiState> = combine(
        transactionRepository.observeById(transactionId), ledgerState
    ) { tx, state ->
        when {
            state.selection != transactionSelection -> DetailUiState.NotFound
            !state.selectedLedger.isLocal && state.isLoading -> DetailUiState.Loading
            tx != null -> DetailUiState.Loaded(tx)
            else -> DetailUiState.NotFound
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState.Loading)

    val categories = categoryRepository.observeAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateTransaction(
        amount: Double,
        categoryId: Long?,
        categoryName: String,
        note: String?,
        date: java.time.LocalDateTime
    ) {
        val current = (uiState.value as? DetailUiState.Loaded)?.transaction ?: return
        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(transactionSelection)
                val category = if (categoryId == null) {
                    categoryRepository.findByNameAndType(categoryName, current.type)
                } else {
                    categoryRepository.getById(categoryId).also {
                        check(it != null && it.type == current.type &&
                            normalizeCategoryName(it.name) == normalizeCategoryName(categoryName)) {
                            "分类已变化，请重新选择"
                        }
                    }
                }
                ledgerContext.requireEditable(transactionSelection)
                transactionRepository.update(
                    current.copy(
                        amount = amount,
                        categoryId = category?.id,
                        categoryName = category?.name ?: categoryName,
                        categoryIcon = category?.icon ?: current.categoryIcon,
                        categoryColor = category?.color ?: current.categoryColor,
                        note = note,
                        date = date,
                        updatedAt = java.time.LocalDateTime.now()
                    )
                ).getOrThrow()
            }.onFailure(::showError)
        }
    }

    fun addCategory(name: String, icon: String = CategoryIconMapper.DEFAULT_ICON_KEY) {
        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(transactionSelection)
                categoryRepository.create(
                    Category(
                        name = name.trim(),
                        icon = icon.trim().ifBlank { CategoryIconMapper.DEFAULT_ICON_KEY },
                        color = "#607D8B",
                        type = (uiState.value as? DetailUiState.Loaded)?.transaction?.type
                            ?: TransactionType.EXPENSE,
                        isSystem = false
                    )
                ).getOrThrow()
            }.onFailure(::showError)
        }
    }

    fun deleteTransaction(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                ledgerContext.requireEditable(transactionSelection)
                transactionRepository.delete(transactionId).getOrThrow()
            }.onSuccess { onDeleted() }.onFailure(::showError)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun showError(error: Throwable) {
        if (error is CancellationException) throw error
        _errorMessage.value = error.message ?: "操作失败"
    }
}
