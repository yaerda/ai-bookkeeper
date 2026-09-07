package com.aibookkeeper.feature.stats.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class CategoryDetailUiState(
    val categoryName: String = "分类明细",
    val yearMonth: YearMonth = YearMonth.now(),
    val transactions: List<Transaction> = emptyList(),
    val total: Double = 0.0,
    val isLoading: Boolean = true,
    val projectId: String? = null
)

@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    transactionRepository: TransactionRepository
) : ViewModel() {

    private val categoryId = checkNotNull(savedStateHandle.get<Long>("categoryId"))
    private val yearMonth = YearMonth.parse(
        checkNotNull(savedStateHandle.get<String>("yearMonth"))
    )
    private val projectId = savedStateHandle.get<String>("projectId")

    val uiState: StateFlow<CategoryDetailUiState> =
        transactionRepository.observeByCategoryAndMonth(categoryId, yearMonth)
            .map { transactions ->
                val selectedTransactions = transactions.filter {
                    projectId == null || projectId in it.projectIds.orEmpty()
                }
                CategoryDetailUiState(
                    categoryName = transactions.firstOrNull()?.categoryName
                        ?: if (categoryId == 0L) "其他" else "分类明细",
                    yearMonth = yearMonth,
                    transactions = selectedTransactions,
                    total = selectedTransactions.sumOf { it.amount },
                    isLoading = false,
                    projectId = projectId
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CategoryDetailUiState(yearMonth = yearMonth)
            )
}
