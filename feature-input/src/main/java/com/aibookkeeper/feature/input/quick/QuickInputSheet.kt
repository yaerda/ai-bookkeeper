package com.aibookkeeper.feature.input.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Quick input bottom-sheet Compose UI.
 * Supports two modes:
 *  1. Free-text: user types natural language, AI extracts, preview → confirm.
 *  2. Category: category preselected, user enters amount only.
 */
@Composable
fun QuickInputSheet(
    viewModel: QuickInputViewModel,
    onDismiss: () -> Unit,
    onOpenFullEditor: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QuickInputSheetContent(
        uiState = uiState,
        onSubmitText = viewModel::submitText,
        onSubmitCategoryAmount = viewModel::submitCategoryAmount,
        onConfirm = viewModel::confirmSave,
        onRetry = viewModel::resetToIdle,
        onDismiss = onDismiss,
        onOpenFullEditor = onOpenFullEditor,
        modifier = modifier
    )
}

@Composable
fun QuickInputSheetContent(
    uiState: QuickInputUiState,
    onSubmitText: (String) -> Unit,
    onSubmitCategoryAmount: (Double, String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFullEditor: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // Dim background with tap-to-dismiss
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Bottom sheet card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* consume click to prevent dismiss */ }
                .navigationBarsPadding()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(16.dp))

                when (uiState) {
                    is QuickInputUiState.Idle -> {
                        if (uiState.preselectedCategory != null) {
                            CategoryAmountInput(
                                categoryName = uiState.preselectedCategory,
                                categoryIcon = uiState.preselectedCategoryIcon ?: "",
                                onSubmit = { amount ->
                                    onSubmitCategoryAmount(amount, uiState.preselectedCategory)
                                },
                                onCancel = onDismiss
                            )
                        } else {
                            TextInput(
                                onSubmit = onSubmitText,
                                onCancel = onDismiss
                            )
                        }
                    }

                    is QuickInputUiState.Extracting -> {
                        LoadingContent(message = "AI 正在分析...")
                    }

                    is QuickInputUiState.Preview -> {
                        PreviewContent(
                            amount = uiState.amount,
                            category = uiState.category,
                            note = uiState.note,
                            date = uiState.date,
                            confidence = uiState.confidence,
                            onConfirm = onConfirm,
                            onEdit = { /* will be handled after save via full editor */ },
                            onCancel = onRetry
                        )
                    }

                    is QuickInputUiState.Saving -> {
                        LoadingContent(message = "保存中...")
                    }

                    is QuickInputUiState.Success -> {
                        SuccessContent(
                            amount = uiState.amount,
                            category = uiState.category,
                            onDone = onDismiss,
                            onEdit = { onOpenFullEditor(uiState.transactionId) }
                        )
                    }

                    is QuickInputUiState.Error -> {
                        ErrorContent(
                            message = uiState.message,
                            onRetry = onRetry,
                            onCancel = onDismiss
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun TextInput(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Text(
        text = "快速记账",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("输入记账内容，如「午饭35」「打车到公司28」") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                onSubmit(text)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("取消")
        }
        Button(
            onClick = {
                keyboardController?.hide()
                onSubmit(text)
            },
            modifier = Modifier.weight(1f),
            enabled = text.isNotBlank()
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("提交")
        }
    }
}

@Composable
private fun CategoryAmountInput(
    categoryName: String,
    categoryIcon: String,
    onSubmit: (Double) -> Unit,
    onCancel: () -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = categoryIcon, fontSize = 28.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = categoryName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = amountText,
        onValueChange = { newValue ->
            // Allow only valid decimal input
            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                amountText = newValue
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("输入金额") },
        prefix = { Text("¥", style = MaterialTheme.typography.titleLarge) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                amountText.toDoubleOrNull()?.let { onSubmit(it) }
            }
        ),
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.headlineSmall
    )
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text("取消")
        }
        Button(
            onClick = {
                keyboardController?.hide()
                amountText.toDoubleOrNull()?.let { onSubmit(it) }
            },
            modifier = Modifier.weight(1f),
            enabled = amountText.toDoubleOrNull() != null && amountText.toDoubleOrNull()!! > 0
        ) {
            Text("确认记账")
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreviewContent(
    amount: Double,
    category: String,
    note: String?,
    date: String,
    confidence: Float,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit
) {
    Text(
        text = "确认记账",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))

    // Amount display
    Text(
        text = "¥${"%.2f".format(amount)}",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(8.dp))

    // Category chip
    Text(
        text = category,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Date display
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "📅 $date",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (note != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }

    if (confidence < 0.7f) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "⚠️ AI 置信度较低，建议确认",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text("重新输入")
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("确认保存")
        }
    }
}

@Composable
private fun SuccessContent(
    amount: Double,
    category: String,
    onDone: () -> Unit,
    onEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "✅", fontSize = 40.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "记账成功",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$category ¥${"%.2f".format(amount)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("详细编辑")
            }
            Button(onClick = onDone) {
                Text("完成")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "❌", fontSize = 40.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("取消")
            }
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0x80000000)
@Composable
private fun QuickInputIdlePreview() {
    MaterialTheme {
        QuickInputSheetContent(
            uiState = QuickInputUiState.Idle(),
            onSubmitText = {},
            onSubmitCategoryAmount = { _, _ -> },
            onConfirm = {},
            onRetry = {},
            onDismiss = {},
            onOpenFullEditor = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0x80000000)
@Composable
private fun QuickInputCategoryPreview() {
    MaterialTheme {
        QuickInputSheetContent(
            uiState = QuickInputUiState.Idle(preselectedCategory = "餐饮", preselectedCategoryIcon = "🍚"),
            onSubmitText = {},
            onSubmitCategoryAmount = { _, _ -> },
            onConfirm = {},
            onRetry = {},
            onDismiss = {},
            onOpenFullEditor = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0x80000000)
@Composable
private fun QuickInputPreviewState() {
    MaterialTheme {
        QuickInputSheetContent(
            uiState = QuickInputUiState.Preview(
                amount = 35.0,
                category = "餐饮",
                note = "午饭",
                date = "2026-03-15",
                confidence = 0.92f,
                originalInput = "午饭35"
            ),
            onSubmitText = {},
            onSubmitCategoryAmount = { _, _ -> },
            onConfirm = {},
            onRetry = {},
            onDismiss = {},
            onOpenFullEditor = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0x80000000)
@Composable
private fun QuickInputSuccessPreview() {
    MaterialTheme {
        QuickInputSheetContent(
            uiState = QuickInputUiState.Success(
                transactionId = 1,
                amount = 35.0,
                category = "餐饮"
            ),
            onSubmitText = {},
            onSubmitCategoryAmount = { _, _ -> },
            onConfirm = {},
            onRetry = {},
            onDismiss = {},
            onOpenFullEditor = {}
        )
    }
}
