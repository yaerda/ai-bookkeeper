package com.aibookkeeper.feature.input.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerSelection
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.TransactionMonthSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.aibookkeeper.core.common.extensions.toFriendlyDateString
import java.time.LocalDate
import java.time.YearMonth
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
    val availableMonths: List<TransactionMonthSummary> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val isLoading: Boolean = true,
    val showFamilyTransactionAuthors: Boolean = false,
    val availableProjects: List<ProjectBinding> = emptyList(),
    val selectedProjectId: String? = null,
    val projectStateMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BillsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val ledgerContext: LedgerContext,
    projectRepository: ProjectRepository
) : ViewModel() {

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    private val _projectFilter = MutableStateFlow<Pair<LedgerSelection, String>?>(null)
    private val projectState = projectRepository.currentLedgerState

    val uiState: StateFlow<BillsUiState> = _currentMonth.flatMapLatest { month ->
        combine(
            transactionRepository.observeByMonth(month),
            transactionRepository.observeTransactionMonths(),
            ledgerContext.state,
            projectState,
            _projectFilter
        ) { transactions, availableMonths, ledgerState, projectLedgerState, projectFilter ->
            val availableProjects = projectLedgerState.projects.takeIf {
                projectLedgerState.ledgerId == ledgerState.selectedLedgerId
            }.orEmpty()
            val selectedProjectId = projectFilter?.takeIf { it.first == ledgerState.selection }
                ?.second?.takeIf { id -> availableProjects.any { it.projectId == id } }
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val filteredTransactions = transactions.filterByProject(selectedProjectId)

            val grouped = filteredTransactions
                .groupBy { it.date.toLocalDate() }
                .toSortedMap(compareByDescending { it })
                .map { (date, txList) ->
                    val label = when (date) {
                        today -> "今天"
                        yesterday -> "昨天"
                        else -> date.toFriendlyDateString()
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
                monthExpense = filteredTransactions
                    .filter { it.type == TransactionType.EXPENSE }
                    .sumOf { it.amount },
                monthIncome = filteredTransactions
                    .filter { it.type == TransactionType.INCOME }
                    .sumOf { it.amount },
                availableMonths = availableMonths,
                dayGroups = grouped,
                isLoading = false,
                showFamilyTransactionAuthors = ledgerState.isSignedIn &&
                    ledgerState.selectedLedger.mode == "FAMILY",
                availableProjects = availableProjects,
                selectedProjectId = selectedProjectId,
                projectStateMessage = projectLedgerState.errorMessage
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

    fun selectMonth(month: YearMonth) {
        if (month <= YearMonth.now()) {
            _currentMonth.value = month
        }
    }

    fun selectProject(projectId: String?) {
        _projectFilter.value = projectId?.let { ledgerContext.state.value.selection to it }
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

    private fun List<Transaction>.filterByProject(projectId: String?): List<Transaction> =
        if (projectId == null) {
            this
        } else {
            filter { projectId in (it.projectIds ?: emptyList()) }
        }
}
