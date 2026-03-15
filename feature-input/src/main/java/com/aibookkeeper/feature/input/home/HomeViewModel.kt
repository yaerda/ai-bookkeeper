package com.aibookkeeper.feature.input.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

data class HomeUiState(
    val todayExpense: Double = 0.0,
    val todayIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val recentTransactions: List<Transaction> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val currentMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val currentMonth = YearMonth.now()

    val uiState: StateFlow<HomeUiState> = combine(
        transactionRepository.observeMonthlyExpense(currentMonth),
        transactionRepository.observeMonthlyIncome(currentMonth),
        transactionRepository.observeByMonth(currentMonth),
        categoryRepository.observeExpenseCategories()
    ) { monthExpense, monthIncome, transactions, categories ->

        val today = LocalDate.now()
        val todayTransactions = transactions.filter { it.date.toLocalDate() == today }
        val todayExpense = todayTransactions
            .filter { it.type == com.aibookkeeper.core.data.model.TransactionType.EXPENSE }
            .sumOf { it.amount }
        val todayIncome = todayTransactions
            .filter { it.type == com.aibookkeeper.core.data.model.TransactionType.INCOME }
            .sumOf { it.amount }

        HomeUiState(
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            monthExpense = monthExpense,
            monthIncome = monthIncome,
            recentTransactions = transactions.take(20),
            expenseCategories = categories,
            currentMonth = currentMonth,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )
}
