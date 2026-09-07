package com.aibookkeeper.feature.stats.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.categoryKey
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerSelection
import com.aibookkeeper.core.data.repository.ProjectRepository
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
    val isLoading: Boolean = true,
    val availableProjects: List<ProjectBinding> = emptyList(),
    val selectedProjectId: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    projectRepository: ProjectRepository,
    private val ledgerContext: LedgerContext
) : ViewModel() {

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    private val _projectFilter = MutableStateFlow<Pair<LedgerSelection, String>?>(null)
    private val projectState = projectRepository.currentLedgerState

    val uiState: StateFlow<StatsUiState> = _currentMonth.flatMapLatest { month ->
        combine(
            transactionRepository.observeByMonth(month),
            transactionRepository.observeExpenseBreakdown(month),
            ledgerContext.state,
            projectState,
            _projectFilter
        ) { transactions, categoryCatalog, ledgerState, projectLedgerState, projectFilter ->
            val availableProjects = projectLedgerState.projects.takeIf {
                projectLedgerState.ledgerId == ledgerState.selectedLedgerId
            }.orEmpty()
            val selectedProjectId = projectFilter?.takeIf { it.first == ledgerState.selection }
                ?.second?.takeIf { id -> availableProjects.any { it.projectId == id } }
            val filteredTransactions = transactions.filterByProject(selectedProjectId)
            val expense = filteredTransactions
                .filter { it.type == TransactionType.EXPENSE }
                .sumOf { it.amount }
            val income = filteredTransactions
                .filter { it.type == TransactionType.INCOME }
                .sumOf { it.amount }
            StatsUiState(
                currentMonth = month,
                monthExpense = expense,
                monthIncome = income,
                balance = income - expense,
                categoryBreakdown = expenseBreakdown(
                    filteredTransactions.filter { it.type == TransactionType.EXPENSE }, categoryCatalog
                ),
                isLoading = !ledgerState.selectedLedger.isLocal && ledgerState.isLoading,
                availableProjects = availableProjects,
                selectedProjectId = selectedProjectId
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

    fun selectProject(projectId: String?) {
        _projectFilter.value = projectId?.let { ledgerContext.state.value.selection to it }
    }

    private fun List<Transaction>.filterByProject(projectId: String?): List<Transaction> =
        if (projectId == null) {
            this
        } else {
            filter { projectId in (it.projectIds ?: emptyList()) }
        }

    private fun expenseBreakdown(
        transactions: List<Transaction>,
        categoryCatalog: List<CategoryExpense>
    ): List<CategoryExpense> {
        val grouped = transactions.groupBy(Transaction::categoryKey)
        val catalog = categoryCatalog.associateBy(CategoryExpense::categoryId)
        val total = transactions.sumOf { it.amount }
        return grouped.map { (categoryId, items) ->
            val amount = items.sumOf { it.amount }
            CategoryExpense(
                categoryId = categoryId,
                categoryName = catalog[categoryId]?.categoryName ?: items.firstOrNull()?.categoryName ?: "其他",
                categoryColor = catalog[categoryId]?.categoryColor ?: items.firstOrNull()?.categoryColor ?: "#607D8B",
                amount = amount,
                percentage = if (total > 0) (amount / total).toFloat() else 0f
            )
        }.sortedByDescending { it.amount }
    }
}
