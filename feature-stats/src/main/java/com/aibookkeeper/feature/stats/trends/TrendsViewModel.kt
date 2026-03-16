package com.aibookkeeper.feature.stats.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

enum class ChartTab { BAR, LINE, YEAR_OVER_YEAR }

data class MonthlyAmount(val yearMonth: YearMonth, val amount: Double)
data class YearAmount(val year: Int, val amount: Double)

data class CategoryInfo(
    val id: Long,
    val name: String,
    val color: String,
    val amount: Double
)

data class TrendsUiState(
    val chartTab: ChartTab = ChartTab.BAR,
    val currentMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = true,

    // Bar chart - category breakdown for current month
    val allCategories: List<CategoryInfo> = emptyList(),
    val selectedCategoryIds: Set<Long> = emptySet(),

    // Line chart - 12-month trend
    val monthlyTrend: List<MonthlyAmount> = emptyList(),
    val trendByCategory: Map<Long, List<MonthlyAmount>> = emptyMap(),
    val selectedTrendCategoryId: Long? = null, // null = total

    // YoY - same month across years
    val yearOverYear: List<YearAmount> = emptyList()
)

@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrendsUiState())
    val uiState: StateFlow<TrendsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun selectTab(tab: ChartTab) {
        _uiState.update { it.copy(chartTab = tab) }
    }

    fun toggleCategory(categoryId: Long) {
        _uiState.update { state ->
            val newSet = state.selectedCategoryIds.toMutableSet()
            if (newSet.contains(categoryId)) newSet.remove(categoryId) else newSet.add(categoryId)
            state.copy(selectedCategoryIds = newSet)
        }
    }

    fun selectAllTopCategories() {
        _uiState.update { state ->
            state.copy(selectedCategoryIds = state.allCategories.take(5).map { it.id }.toSet())
        }
    }

    fun selectTrendCategory(categoryId: Long?) {
        _uiState.update { it.copy(selectedTrendCategoryId = categoryId) }
    }

    fun setMonth(yearMonth: YearMonth) {
        _uiState.update { it.copy(currentMonth = yearMonth) }
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val currentMonth = _uiState.value.currentMonth

            // Bar chart: category breakdown
            val breakdown = transactionRepository.getCategoryBreakdownOnce("EXPENSE", currentMonth)
            val categories = breakdown.map {
                CategoryInfo(it.categoryId, it.categoryName, it.categoryColor, it.amount)
            }
            val defaultSelected = categories.take(5).map { it.id }.toSet()

            // Line chart: 12-month trend (total)
            val monthlyTrend = mutableListOf<MonthlyAmount>()
            for (i in 11 downTo 0) {
                val month = currentMonth.minusMonths(i.toLong())
                val amount = transactionRepository.getMonthlyExpense(month)
                monthlyTrend.add(MonthlyAmount(month, amount))
            }

            // Line chart: per-category trend for top categories
            val topCategoryIds = categories.take(10).map { it.id }
            val trendByCategory = mutableMapOf<Long, List<MonthlyAmount>>()
            for (catId in topCategoryIds) {
                val catTrend = mutableListOf<MonthlyAmount>()
                for (i in 11 downTo 0) {
                    val month = currentMonth.minusMonths(i.toLong())
                    val catBreakdown = transactionRepository.getCategoryBreakdownOnce("EXPENSE", month)
                    val catAmount = catBreakdown.find { it.categoryId == catId }?.amount ?: 0.0
                    catTrend.add(MonthlyAmount(month, catAmount))
                }
                trendByCategory[catId] = catTrend
            }

            // YoY: same month across years
            val yearOverYear = mutableListOf<YearAmount>()
            val currentYear = currentMonth.year
            for (year in (currentYear - 4)..currentYear) {
                val ym = YearMonth.of(year, currentMonth.monthValue)
                val amount = transactionRepository.getMonthlyExpense(ym)
                if (amount > 0 || year == currentYear) {
                    yearOverYear.add(YearAmount(year, amount))
                }
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    allCategories = categories,
                    selectedCategoryIds = if (state.selectedCategoryIds.isEmpty()) defaultSelected
                                         else state.selectedCategoryIds,
                    monthlyTrend = monthlyTrend,
                    trendByCategory = trendByCategory,
                    yearOverYear = yearOverYear
                )
            }
        }
    }
}
