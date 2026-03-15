package com.aibookkeeper.feature.input.text

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextInputScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    initialCategoryId: Long? = null,
    viewModel: TextInputViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Scaffold(
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = null,
                    indication = null
                ) { focusManager.clearFocus() }
                .padding(16.dp)
        ) {
            when (uiState) {
                is TextInputUiState.Idle -> {
                    AiInputSection(
                        categories = categories,
                        initialCategoryId = initialCategoryId,
                        onSubmitText = viewModel::submitText,
                        onManualSave = { amount, categoryId, categoryName, note, type ->
                            viewModel.saveManual(amount, categoryId, categoryName, note, type)
                        },
                        onAddCategory = viewModel::addCategory
                    )
                }
                is TextInputUiState.Extracting -> {
                    ExtractingSection()
                }
                is TextInputUiState.Preview -> {
                    val preview = uiState as TextInputUiState.Preview
                    PreviewSection(
                        preview = preview,
                        onConfirm = viewModel::confirmSave,
                        onRetry = viewModel::resetToIdle
                    )
                }
                is TextInputUiState.Saving -> {
                    SavingSection()
                }
                is TextInputUiState.Success -> {
                    val success = uiState as TextInputUiState.Success
                    SuccessSection(
                        amount = success.amount,
                        category = success.category,
                        onDone = { navController.popBackStack() },
                        onContinue = viewModel::resetToIdle
                    )
                }
                is TextInputUiState.Error -> {
                    val error = uiState as TextInputUiState.Error
                    ErrorSection(
                        message = error.message,
                        onRetry = viewModel::resetToIdle
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiInputSection(
    categories: List<Category>,
    initialCategoryId: Long? = null,
    onSubmitText: (String) -> Unit,
    onManualSave: (Double, Long?, String, String?, TransactionType) -> Unit,
    onAddCategory: (String) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val initialCategory = remember(initialCategoryId, categories) {
        if (initialCategoryId != null) categories.find { it.id == initialCategoryId } else null
    }
    var showManualForm by remember(initialCategory) { mutableStateOf(initialCategory != null) }
    var gridSelectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // AI text input
    Text(
        text = "✨ 智能记账",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "输入一句话，AI 帮你自动识别",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )

    OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("例如：午饭35、打车到公司15、星巴克拿铁28") },
        trailingIcon = {
            if (inputText.isNotBlank()) {
                IconButton(onClick = {
                    keyboardController?.hide()
                    onSubmitText(inputText)
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "提交",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                if (inputText.isNotBlank()) onSubmitText(inputText)
            }
        ),
        shape = RoundedCornerShape(16.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = {
            keyboardController?.hide()
            if (inputText.isNotBlank()) onSubmitText(inputText)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = inputText.isNotBlank(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("AI 识别并记账")
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Quick category grid
    Text(
        text = "🏷️ 快速分类",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 4
    ) {
        val visibleCategories = categories.take(8)
        visibleCategories.forEach { cat ->
            CategoryGridItem(
                category = cat,
                onClick = {
                    gridSelectedCategory = cat
                    showManualForm = true
                },
                modifier = Modifier.weight(1f)
            )
        }
        // Fill remaining slots so last row items stay same width as others
        val remainder = visibleCategories.size % 4
        if (remainder != 0) {
            repeat(4 - remainder) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Manual form toggle
    TextButton(
        onClick = { showManualForm = !showManualForm },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(if (showManualForm) "收起手动输入" else "手动输入")
    }

    AnimatedVisibility(visible = showManualForm) {
        ManualInputForm(
            categories = categories,
            onSave = onManualSave,
            onAddCategory = onAddCategory,
            initialCategory = gridSelectedCategory
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualInputForm(
    categories: List<Category>,
    onSave: (Double, Long?, String, String?, TransactionType) -> Unit,
    onAddCategory: (String) -> Unit = {},
    initialCategory: Category? = null
) {
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var note by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }

    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Type toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilterChip(
                    selected = isExpense,
                    onClick = { isExpense = true },
                    label = { Text("支出") }
                )
                Spacer(modifier = Modifier.width(12.dp))
                FilterChip(
                    selected = !isExpense,
                    onClick = { isExpense = false },
                    label = { Text("收入") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Amount
            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                        amountText = newValue
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("金额") },
                prefix = { Text("¥") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Category
            Text(
                text = "分类",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val emoji = CategoryIconMapper.getEmoji(cat.icon)
                    FilterChip(
                        selected = selectedCategory?.id == cat.id,
                        onClick = { selectedCategory = cat },
                        label = { Text("$emoji ${cat.name}") }
                    )
                }
                // Add custom category button
                FilterChip(
                    selected = false,
                    onClick = { showAddCategoryDialog = true },
                    label = { Text("＋ 新分类") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Note
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（选填）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@Button
                    val catName = selectedCategory?.name ?: "其他"
                    val type = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
                    onSave(amount, selectedCategory?.id, catName, note.ifBlank { null }, type)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = amountText.toDoubleOrNull() != null && (amountText.toDoubleOrNull() ?: 0.0) > 0,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
            }
        }
    }

    if (showAddCategoryDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false; newCategoryName = "" },
            title = { Text("添加新分类") },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newCategoryName.isNotBlank()) {
                            onAddCategory(newCategoryName)
                            showAddCategoryDialog = false
                            newCategoryName = ""
                        }
                    },
                    enabled = newCategoryName.isNotBlank()
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false; newCategoryName = "" }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ExtractingSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "🧠 AI 正在分析...",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "正在识别金额、分类和商户信息",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun PreviewSection(
    preview: TextInputUiState.Preview,
    onConfirm: () -> Unit,
    onRetry: () -> Unit
) {
    Text(
        text = "✅ 识别结果",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Amount
            Text(
                text = "¥${"%.2f".format(preview.amount)}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Category
            Text(
                text = preview.category,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Merchant
            if (!preview.merchantName.isNullOrBlank()) {
                Text(
                    text = "🏪 ${preview.merchantName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Note
            if (!preview.note.isNullOrBlank()) {
                Text(
                    text = "📝 ${preview.note}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Date
            Text(
                text = "📅 ${preview.date}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Confidence
            if (preview.confidence < 0.7f) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠️ AI 置信度较低 (${"%.0f".format(preview.confidence * 100)}%)，请确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Original text
            Text(
                text = "原文: ${preview.originalInput}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("重新输入")
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("确认保存")
        }
    }
}

@Composable
private fun SavingSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "保存中...",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun SuccessSection(
    amount: Double,
    category: String,
    onDone: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "✅", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "记账成功！",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$category  ¥${"%.2f".format(amount)}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = onDone,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("返回首页")
            }
            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("继续记账")
            }
        }
    }
}

@Composable
private fun ErrorSection(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "❌", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("重试")
        }
    }
}

@Composable
private fun CategoryGridItem(
    category: Category,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val emoji = CategoryIconMapper.getEmoji(category.icon)
    val bgColor = parseCategoryColor(category.color)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(bgColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 24.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
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
private fun TextInputScreenPreview() {
    MaterialTheme {
        TextInputScreen(navController = rememberNavController())
    }
}
