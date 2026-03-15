package com.aibookkeeper.feature.input.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.ExtractionSource
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

private data class DataTuple(
    val monthExpense: Double,
    val monthIncome: Double,
    val transactions: List<Transaction>,
    val categories: List<Category>
)

data class HomeUiState(
    val todayExpense: Double = 0.0,
    val todayIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val recentTransactions: List<Transaction> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val currentMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = true,
    val aiStatus: AiStatus = AiStatus.Idle
)

sealed class AiStatus {
    data object Idle : AiStatus()
    data object Processing : AiStatus()
    data class Success(val message: String) : AiStatus()
    data class Error(val message: String) : AiStatus()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val aiExtractionRepository: AiExtractionRepository
) : ViewModel() {

    private val _aiStatus = MutableStateFlow<AiStatus>(AiStatus.Idle)

    private val currentMonth = YearMonth.now()

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            transactionRepository.observeMonthlyExpense(currentMonth),
            transactionRepository.observeMonthlyIncome(currentMonth),
            transactionRepository.observeByMonth(currentMonth),
            categoryRepository.observeExpenseCategories()
        ) { monthExpense, monthIncome, transactions, categories ->
            DataTuple(monthExpense, monthIncome, transactions, categories)
        },
        _aiStatus
    ) { data, aiStatus ->

        val today = LocalDate.now()
        val todayTransactions = data.transactions.filter { it.date.toLocalDate() == today }
        val todayExpense = todayTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
        val todayIncome = todayTransactions
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amount }

        HomeUiState(
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            monthExpense = data.monthExpense,
            monthIncome = data.monthIncome,
            recentTransactions = data.transactions.take(20),
            expenseCategories = data.categories,
            currentMonth = currentMonth,
            isLoading = false,
            aiStatus = aiStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun submitAiInput(text: String) {
        viewModelScope.launch {
            _aiStatus.value = AiStatus.Processing
            try {
                val categories = categoryRepository.observeExpenseCategories().stateIn(viewModelScope).value
                val categoryNames = categories.map { it.name }

                // Split multi-line input into separate entries
                val lines = text.split("\n", "，", "。", "；")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 1 }

                val results = mutableListOf<String>()
                for (line in lines) {
                    try {
                        val result = aiExtractionRepository.extract(line, categoryNames).getOrThrow()
                        val amount = result.amount ?: continue
                        if (amount <= 0) continue
                        val type = if (result.type.equals("income", true)) TransactionType.INCOME else TransactionType.EXPENSE
                        val matchedCategory = categories.find { it.name == result.category }
                        val date = try { LocalDate.parse(result.date) } catch (_: Exception) { LocalDate.now() }

                        val transaction = Transaction(
                            amount = amount,
                            type = type,
                            categoryId = matchedCategory?.id,
                            categoryName = matchedCategory?.name ?: result.category,
                            categoryIcon = matchedCategory?.icon,
                            categoryColor = matchedCategory?.color,
                            merchantName = result.merchantName,
                            note = result.note ?: line,
                            originalInput = line,
                            date = date.atStartOfDay(),
                            createdAt = LocalDateTime.now(),
                            updatedAt = LocalDateTime.now(),
                            source = if (result.source == ExtractionSource.AZURE_AI) TransactionSource.TEXT_AI else TransactionSource.MANUAL,
                            status = TransactionStatus.CONFIRMED,
                            syncStatus = SyncStatus.LOCAL,
                            aiConfidence = result.confidence
                        )
                        transactionRepository.create(transaction)
                        results.add("${result.category} ${amount}元")
                    } catch (_: Exception) { /* skip failed lines */ }
                }

                if (results.isNotEmpty()) {
                    _aiStatus.value = AiStatus.Success("已记${results.size}笔：${results.joinToString("、")}")
                } else {
                    _aiStatus.value = AiStatus.Error("未识别到有效账单")
                }
            } catch (e: Exception) {
                _aiStatus.value = AiStatus.Error(e.message ?: "识别失败")
            }
        }
    }

    fun resetAiStatus() {
        _aiStatus.value = AiStatus.Idle
    }
}
