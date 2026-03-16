package com.aibookkeeper.feature.stats.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrendsScreen(
    navController: NavController,
    viewModel: TrendsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tabs = listOf("📊 分类柱状图", "📈 月度趋势", "📅 同比对比")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("消费趋势") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            TabRow(
                selectedTabIndex = state.chartTab.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = state.chartTab.ordinal == index,
                        onClick = { viewModel.selectTab(ChartTab.entries[index]) },
                        text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (state.chartTab) {
                        ChartTab.BAR -> BarChartSection(state, viewModel)
                        ChartTab.LINE -> LineChartSection(state, viewModel)
                        ChartTab.YEAR_OVER_YEAR -> YoYChartSection(state)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarChartSection(state: TrendsUiState, viewModel: TrendsViewModel) {
    val selectedCategories = state.allCategories.filter { it.id in state.selectedCategoryIds }

    Text(
        text = "${state.currentMonth.monthValue}月 分类消费对比",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    if (selectedCategories.isNotEmpty()) {
        val amounts = selectedCategories.map { it.amount }
        val labels = selectedCategories.map { it.name }

        // Pre-create colored columns for each category
        val coloredColumns = remember(selectedCategories) {
            selectedCategories.map { cat ->
                LineComponent(
                    color = parseCategoryColorInt(cat.color),
                    thicknessDp = 16f
                )
            }
        }
        val columnProvider = remember(coloredColumns) {
            object : ColumnCartesianLayer.ColumnProvider {
                override fun getColumn(
                    entry: ColumnCartesianLayerModel.Entry,
                    seriesIndex: Int,
                    extraStore: ExtraStore
                ): LineComponent {
                    val idx = entry.x.toInt().coerceIn(0, coloredColumns.lastIndex)
                    return coloredColumns[idx]
                }
                override fun getWidestSeriesColumn(
                    seriesIndex: Int,
                    extraStore: ExtraStore
                ): LineComponent = coloredColumns.first()
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(columnProvider = columnProvider),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = CartesianValueFormatter { _, value, _ ->
                                labels.getOrElse(value.toInt()) { "" }
                            }
                        )
                    ),
                    model = CartesianChartModel(
                        ColumnCartesianLayerModel.build {
                            series(amounts)
                        }
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Amount labels below chart
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            selectedCategories.forEach { cat ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(parseCategoryColor(cat.color))
                    )
                    Text(cat.name, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "¥${"%.0f".format(cat.amount)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    } else {
        EmptyChartState("暂无消费数据")
    }

    // Category selection
    Text(
        text = "选择分类（前10）",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        state.allCategories.take(10).forEach { cat ->
            FilterChip(
                selected = cat.id in state.selectedCategoryIds,
                onClick = { viewModel.toggleCategory(cat.id) },
                label = {
                    Text("${cat.name} ¥${"%.0f".format(cat.amount)}")
                }
            )
        }
    }
    if (state.allCategories.size > 10) {
        Text(
            text = "更多分类请在统计页查看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LineChartSection(state: TrendsUiState, viewModel: TrendsViewModel) {
    Text(
        text = "过去12个月消费趋势",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    // Get data based on selected category or total
    val trendData = if (state.selectedTrendCategoryId == null) {
        state.monthlyTrend
    } else {
        state.trendByCategory[state.selectedTrendCategoryId] ?: emptyList()
    }

    if (trendData.isNotEmpty() && trendData.any { it.amount > 0 }) {
        val amounts = trendData.map { it.amount }
        val labels = trendData.map { "${it.yearMonth.monthValue}月" }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = CartesianValueFormatter { _, value, _ ->
                                labels.getOrElse(value.toInt()) { "" }
                            }
                        )
                    ),
                    model = CartesianChartModel(
                        LineCartesianLayerModel.build {
                            series(amounts)
                        }
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else {
        EmptyChartState("暂无趋势数据")
    }

    // Category filter
    Text(
        text = "按分类查看",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = state.selectedTrendCategoryId == null,
            onClick = { viewModel.selectTrendCategory(null) },
            label = { Text("全部") }
        )
        state.allCategories.take(10).forEach { cat ->
            FilterChip(
                selected = state.selectedTrendCategoryId == cat.id,
                onClick = { viewModel.selectTrendCategory(cat.id) },
                label = { Text(cat.name) }
            )
        }
    }
}

@Composable
private fun YoYChartSection(state: TrendsUiState) {
    Text(
        text = "${state.currentMonth.monthValue}月 历年同期对比",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    if (state.yearOverYear.isNotEmpty() && state.yearOverYear.any { it.amount > 0 }) {
        val amounts = state.yearOverYear.map { it.amount }
        val labels = state.yearOverYear.map { "${it.year}" }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = CartesianValueFormatter { _, value, _ ->
                                labels.getOrElse(value.toInt()) { "" }
                            }
                        )
                    ),
                    model = CartesianChartModel(
                        ColumnCartesianLayerModel.build {
                            series(amounts)
                        }
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Amount labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            state.yearOverYear.forEach { ya ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${ya.year}年", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "¥${"%.0f".format(ya.amount)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (ya.year == state.currentMonth.year)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    } else {
        EmptyChartState("暂无历年数据")
    }
}

@Composable
private fun EmptyChartState(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun parseCategoryColor(color: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(color))
    } catch (_: Exception) {
        Color(0xFF607D8B)
    }
}

private fun parseCategoryColorInt(color: String): Int {
    return try {
        android.graphics.Color.parseColor(color)
    } catch (_: Exception) {
        0xFF607D8B.toInt()
    }
}
