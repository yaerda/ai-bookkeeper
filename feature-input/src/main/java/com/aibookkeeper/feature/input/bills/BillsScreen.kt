package com.aibookkeeper.feature.input.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: BillsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("账单") })
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Month selector
            MonthSelector(
                currentMonth = uiState.currentMonth,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth
            )

            // Monthly summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "支出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "¥${"%.2f".format(uiState.monthExpense)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "收入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "¥${"%.2f".format(uiState.monthIncome)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            HorizontalDivider()

            if (uiState.dayGroups.isEmpty() && !uiState.isLoading) {
                // Empty state
                EmptyBillsState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    uiState.dayGroups.forEach { dayGroup ->
                        // Day header
                        item(key = "header_${dayGroup.date}") {
                            DayHeader(dayGroup = dayGroup)
                        }

                        // Transactions for this day
                        items(
                            items = dayGroup.transactions,
                            key = { it.id }
                        ) { transaction ->
                            TransactionItem(
                                transaction = transaction,
                                onClick = {
                                    navController.navigate("transaction/${transaction.id}")
                                }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    currentMonth: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上个月"
            )
        }

        Text(
            text = "${currentMonth.year}年${currentMonth.monthValue}月",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        val isCurrentMonth = currentMonth == YearMonth.now()
        IconButton(onClick = onNext, enabled = !isCurrentMonth) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下个月",
                tint = if (isCurrentMonth) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DayHeader(dayGroup: DayGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayGroup.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
        Row {
            if (dayGroup.dayExpense > 0) {
                Text(
                    text = "支出 ¥${"%.2f".format(dayGroup.dayExpense)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (dayGroup.dayIncome > 0) {
                if (dayGroup.dayExpense > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "收入 ¥${"%.2f".format(dayGroup.dayIncome)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
private fun TransactionItem(
    transaction: Transaction,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Category emoji
        val emoji = CategoryIconMapper.getEmoji(transaction.categoryIcon)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    parseCategoryColor(transaction.categoryColor).copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.categoryName ?: "未分类",
                style = MaterialTheme.typography.bodyLarge
            )
            if (transaction.note?.isNotBlank() == true) {
                Text(
                    text = transaction.note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        Text(
            text = "${if (transaction.type == TransactionType.INCOME) "+" else "-"}¥${"%.2f".format(transaction.amount)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (transaction.type == TransactionType.INCOME) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

@Composable
private fun EmptyBillsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无账单记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "点击底部「记账」开始记录吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun parseCategoryColor(colorStr: String?): Color {
    if (colorStr.isNullOrBlank()) return Color(0xFF607D8B)
    return try {
        Color(android.graphics.Color.parseColor(colorStr))
    } catch (_: Exception) {
        Color(0xFF607D8B)
    }
}

@Preview(showBackground = true)
@Composable
private fun BillsScreenPreview() {
    MaterialTheme {
        BillsScreen(navController = rememberNavController())
    }
}
