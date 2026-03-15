package com.aibookkeeper.feature.input.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aibookkeeper.core.common.extensions.toFriendlyDateTimeString
import com.aibookkeeper.core.common.extensions.toFriendlyFullDateTimeString
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: TransactionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单详情") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is DetailUiState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "😕", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "记录不存在",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { navController.popBackStack() }) {
                            Text("返回")
                        }
                    }
                }
            }

            is DetailUiState.Deleted -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "🗑️", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "已删除",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is DetailUiState.Loaded -> {
                val categories by viewModel.categories.collectAsStateWithLifecycle()
                TransactionDetailContent(
                    transaction = state.transaction,
                    categories = categories,
                    onDelete = {
                        viewModel.deleteTransaction { navController.popBackStack() }
                    },
                    onUpdate = { amount, catId, catName, note, date ->
                        viewModel.updateTransaction(amount, catId, catName, note, date)
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailContent(
    transaction: Transaction,
    categories: List<com.aibookkeeper.core.data.model.Category>,
    onDelete: () -> Unit,
    onUpdate: (Double, Long?, String, String?, java.time.LocalDateTime) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editAmount by remember(transaction.id) { mutableStateOf("%.2f".format(transaction.amount)) }
    var editNote by remember(transaction.id) { mutableStateOf(transaction.note ?: "") }
    var editCategoryId by remember(transaction.id) { mutableStateOf(transaction.categoryId) }
    var editCategoryName by remember(transaction.id) { mutableStateOf(transaction.categoryName ?: "") }
    var editDate by remember(transaction.id) { mutableStateOf(transaction.date) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Amount card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (transaction.type == TransactionType.INCOME)
                    Color(0xFF4CAF50).copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Category emoji
                val emoji = CategoryIconMapper.getEmoji(transaction.categoryIcon)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            parseCategoryColor(transaction.categoryColor).copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 32.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = transaction.categoryName ?: "未分类",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${if (transaction.type == TransactionType.INCOME) "+" else "-"}¥${"%.2f".format(transaction.amount)}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.type == TransactionType.INCOME) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.onSurface
                )

                if (transaction.status == TransactionStatus.PENDING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⏳ 待确认",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Detail info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (isEditing) {
                    // Amount (numeric keyboard)
                    OutlinedTextField(
                        value = editAmount,
                        onValueChange = { v ->
                            if (v.isEmpty() || v.matches(Regex("^\\d*\\.?\\d{0,2}$"))) editAmount = v
                        },
                        label = { Text("金额") },
                        prefix = { Text("¥") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Note
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Date picker
                    Text("日期", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true }
                    ) {
                        OutlinedTextField(
                            value = editDate.toLocalDate().toString(),
                            onValueChange = {},
                            readOnly = true,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "选择日期",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        // Transparent overlay to capture clicks (readOnly TextField doesn't propagate clicks)
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { showDatePicker = true }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Category selection
                    Text("分类", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { cat ->
                            val emoji = CategoryIconMapper.getEmoji(cat.icon)
                            val isSelected = editCategoryId == cat.id
                            if (isSelected) {
                                androidx.compose.material3.AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            "✅ $emoji ${cat.name}",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    },
                                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    border = null
                                )
                            } else {
                                FilterChip(
                                    selected = false,
                                    onClick = { editCategoryId = cat.id; editCategoryName = cat.name },
                                    label = { Text("$emoji ${cat.name}") }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { isEditing = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("取消") }
                        Button(
                            onClick = {
                                val amount = editAmount.toDoubleOrNull() ?: transaction.amount
                                onUpdate(amount, editCategoryId, editCategoryName, editNote.ifBlank { null }, editDate)
                                isEditing = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("保存") }
                    }

                    // DatePicker dialog
                    if (showDatePicker) {
                        val datePickerState = rememberDatePickerState(
                            initialSelectedDateMillis = editDate.toLocalDate()
                                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        )
                        DatePickerDialog(
                            onDismissRequest = { showDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    datePickerState.selectedDateMillis?.let { millis ->
                                        val ld = java.time.Instant.ofEpochMilli(millis)
                                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                        editDate = ld.atStartOfDay()
                                    }
                                    showDatePicker = false
                                }) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                            }
                        ) {
                            DatePicker(state = datePickerState)
                        }
                    }
                } else {
                    // Read-only view
                    DetailRow(label = "类型", value = if (transaction.type == TransactionType.INCOME) "收入" else "支出")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow(label = "时间", value = transaction.date.toFriendlyDateTimeString())

                    if (!transaction.merchantName.isNullOrBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(label = "商户", value = transaction.merchantName.orEmpty())
                    }

                    if (!transaction.note.isNullOrBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(label = "备注", value = transaction.note.orEmpty())
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow(label = "来源", value = formatSource(transaction.source))

                    if (transaction.aiConfidence != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(
                            label = "AI 置信度",
                            value = "${"%.0f".format((transaction.aiConfidence ?: 0f) * 100)}%"
                        )
                    }

                    if (!transaction.originalInput.isNullOrBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(label = "原始输入", value = transaction.originalInput.orEmpty())
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow(label = "创建时间", value = transaction.createdAt.toFriendlyFullDateTimeString())
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Edit button
        if (!isEditing) {
            Button(
                onClick = { isEditing = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("编辑")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Delete button
        Button(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("删除记录")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatSource(source: TransactionSource): String = when (source) {
    TransactionSource.MANUAL -> "手动输入"
    TransactionSource.TEXT_AI -> "文字 AI"
    TransactionSource.VOICE_AI -> "语音 AI"
    TransactionSource.PHOTO_AI -> "拍照 AI"
    TransactionSource.AUTO_CAPTURE -> "自动捕获"
    TransactionSource.NOTIFICATION_QUICK -> "通知栏快捷"
}

private fun parseCategoryColor(colorStr: String?): Color {
    if (colorStr.isNullOrBlank()) return Color(0xFF607D8B)
    return try {
        Color(android.graphics.Color.parseColor(colorStr))
    } catch (_: Exception) {
        Color(0xFF607D8B)
    }
}
