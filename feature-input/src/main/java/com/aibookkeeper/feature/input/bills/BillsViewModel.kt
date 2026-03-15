package com.aibookkeeper.feature.input.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DayGroup(
    val date: LocalDate,
    val label: String,
    val dayExpense: Double,
    val dayIncome: Double,
    val transactions: List<Transaction>
)

data class BillsUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val dayGroups: List<DayGroup> = emptyList(),
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BillsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _currentMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<BillsUiState> = _currentMonth.flatMapLatest { month ->
        combine(
            transactionRepository.observeByMonth(month),
            transactionRepository.observeMonthlyExpense(month),
            transactionRepository.observeMonthlyIncome(month)
        ) { transactions, expense, income ->
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)

            val grouped = transactions
                .groupBy { it.date.toLocalDate() }
                .toSortedMap(compareByDescending { it })
                .map { (date, txList) ->
                    val label = when (date) {
                        today -> "今天"
                        yesterday -> "昨天"
                        else -> date.format(DateTimeFormatter.ofPattern("MM月dd日 E"))
                    }
                    DayGroup(
                        date = date,
                        label = label,
                        dayExpense = txList.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount },
                        dayIncome = txList.filter { it.type == TransactionType.INCOME }.sumOf { it.amount },
                        transactions = txList
                    )
                }

            BillsUiState(
                currentMonth = month,
                monthExpense = expense,
                monthIncome = income,
                dayGroups = grouped,
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BillsUiState()
    )

    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            transactionRepository.delete(id)
        }
    }

    fun undoDelete(transaction: Transaction) {
        viewModelScope.launch {
            transactionRepository.create(transaction)
        }
    }
}
