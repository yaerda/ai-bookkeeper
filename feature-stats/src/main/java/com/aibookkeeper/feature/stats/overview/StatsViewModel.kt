package com.aibookkeeper.feature.stats.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class StatsUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val balance: Double = 0.0,
    val categoryBreakdown: List<CategoryExpense> = emptyList(),
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _currentMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<StatsUiState> = _currentMonth.flatMapLatest { month ->
        combine(
            transactionRepository.observeMonthlyExpense(month),
            transactionRepository.observeMonthlyIncome(month),
            transactionRepository.observeExpenseBreakdown(month)
        ) { expense, income, breakdown ->
            StatsUiState(
                currentMonth = month,
                monthExpense = expense,
                monthIncome = income,
                balance = income - expense,
                categoryBreakdown = breakdown,
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatsUiState()
    )

    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
    }
}
