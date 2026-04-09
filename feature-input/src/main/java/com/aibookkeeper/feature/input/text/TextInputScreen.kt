package com.aibookkeeper.feature.input.text

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.feature.input.common.AddCategoryDialog
import com.aibookkeeper.feature.input.common.CategoryIconEditor
import com.aibookkeeper.feature.input.common.CategoryNameAndEmojiFields
import com.aibookkeeper.feature.input.common.resolveCategoryIcon
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.feature.input.home.VoiceInputMode
import com.aibookkeeper.feature.input.home.VoiceStatus
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
                        navController = navController,
                        categories = categories,
                        initialCategoryId = initialCategoryId,
                        viewModel = viewModel,
                        onSubmitText = viewModel::submitText,
                        onManualSave = { amount, categoryId, categoryName, note, type ->
                            viewModel.saveManual(amount, categoryId, categoryName, note, type)
                        },
                        onAddCategory = viewModel::addCategory,
                        onUpdateCategory = { cat, name, icon -> viewModel.updateCategory(cat, name, icon) }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AiInputSection(
    navController: NavController,
    categories: List<Category>,
    initialCategoryId: Long? = null,
    viewModel: TextInputViewModel,
    onSubmitText: (String) -> Unit,
    onManualSave: (Double, Long?, String, String?, TransactionType) -> Unit,
    onAddCategory: (String, String) -> Unit = { _, _ -> },
    onUpdateCategory: (Category, String, String) -> Unit = { _, _, _ -> }
) {
    var inputText by remember { mutableStateOf("") }
    val initialCategory = remember(initialCategoryId, categories) {
        if (initialCategoryId != null) categories.find { it.id == initialCategoryId } else null
    }
    var showManualForm by remember(initialCategory) { mutableStateOf(initialCategory != null) }
    var gridSelectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryPresetIcon by remember { mutableStateOf(CategoryIconMapper.DEFAULT_ICON_KEY) }
    var newCategoryCustomEmoji by remember { mutableStateOf("") }
    var pendingVoiceRequest by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()

    // Local SpeechRecognizer (no system UI)
    val speechRecognizer = remember {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    // Set up recognition listener
    remember(speechRecognizer) {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isRecording = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) {
                    inputText = if (inputText.isBlank()) text else "$inputText\n$text"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onError(error: Int) {
                isRecording = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音内容"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                    SpeechRecognizer.ERROR_NETWORK -> "网络不可用，请检查连接"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    else -> "语音识别失败 (错误码: $error)"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isRecording = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        true // return value for remember
    }

    fun startLocalSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        isRecording = true
        speechRecognizer.startListening(intent)
    }

    fun stopLocalSpeechRecognition() {
        speechRecognizer.stopListening()
        isRecording = false
    }

    fun resetNewCategoryDraft() {
        newCategoryName = ""
        newCategoryPresetIcon = CategoryIconMapper.DEFAULT_ICON_KEY
        newCategoryCustomEmoji = ""
    }

    fun submitInput() {
        keyboardController?.hide()
        if (inputText.isNotBlank()) {
            onSubmitText(inputText)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingVoiceRequest = false
            startLocalSpeechRecognition()
        } else {
            pendingVoiceRequest = false
            Toast.makeText(context, "请授予麦克风权限后再使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoiceWithPermissionGuard() {
        if (context.hasAudioPermission()) {
            if (isRecording) {
                stopLocalSpeechRecognition()
            } else {
                startLocalSpeechRecognition()
            }
        } else if (!pendingVoiceRequest) {
            pendingVoiceRequest = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(voiceStatus) {
        when (val status = voiceStatus) {
            is VoiceStatus.Success -> {
                inputText = if (inputText.isBlank()) status.text else "$inputText\n${status.text}"
                viewModel.resetVoiceStatus()
            }
            is VoiceStatus.Error -> {
                Toast.makeText(context, status.message, Toast.LENGTH_LONG).show()
                viewModel.resetVoiceStatus()
            }
            else -> Unit
        }
    }

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
                IconButton(onClick = ::submitInput) {
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
        keyboardActions = KeyboardActions(onDone = { submitInput() }),
        shape = RoundedCornerShape(16.dp)
    )

    // Camera & file shortcut buttons
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { navController.navigate("capture/camera") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("📷 拍照", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { navController.navigate("capture/camera") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("🖼️ 相册", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { navController.navigate("capture/camera") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("📁 文件", style = MaterialTheme.typography.labelMedium)
        }
    }

    AiActionButton(
        aiInput = inputText,
        isRecording = isRecording,
        isSubmitting = false,
        voiceStatus = voiceStatus,
        onSubmit = ::submitInput,
        onVoiceToggle = {
            when {
                voiceStatus is VoiceStatus.Processing -> {
                    Toast.makeText(context, "正在识别中，请稍候", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    startVoiceWithPermissionGuard()
                }
            }
        }
    )

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
                onLongClick = { editingCategory = cat },
                modifier = Modifier.weight(1f)
            )
        }
        val remainder = visibleCategories.size % 4
        if (remainder != 0) {
            repeat(4 - remainder) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = { showManualForm = !showManualForm },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (showManualForm) "收起手动输入" else "✏️ 手动输入")
        }
        OutlinedButton(
            onClick = { showAddCategoryDialog = true },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("＋ 新增分类")
        }
    }

    if (showManualForm) {
        Dialog(
            onDismissRequest = { showManualForm = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("手动记账") },
                        navigationIcon = {
                            IconButton(onClick = { showManualForm = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ManualInputForm(
                        categories = categories,
                        onSave = { amount, catId, catName, note, type ->
                            onManualSave(amount, catId, catName, note, type)
                            showManualForm = false
                        },
                        onOpenAddCategoryDialog = { showAddCategoryDialog = true },
                        initialCategory = gridSelectedCategory
                    )
                }
            }
        }
    }

    if (showAddCategoryDialog) {
        AddCategoryDialog(
            name = newCategoryName,
            presetIcon = newCategoryPresetIcon,
            customEmoji = newCategoryCustomEmoji,
            onNameChange = { if (it.length <= 6) newCategoryName = it },
            onPresetIconSelected = { iconKey ->
                newCategoryPresetIcon = iconKey
                newCategoryCustomEmoji = CategoryIconMapper.getEmoji(iconKey)
            },
            onCustomEmojiChange = { emoji ->
                if (emoji.length <= 16) {
                    newCategoryCustomEmoji = emoji
                }
            },
            onDismiss = {
                showAddCategoryDialog = false
                resetNewCategoryDraft()
            },
            onConfirm = {
                onAddCategory(
                    newCategoryName,
                    resolveCategoryIcon(newCategoryPresetIcon, newCategoryCustomEmoji)
                )
                showAddCategoryDialog = false
                resetNewCategoryDraft()
            }
        )
    }

    editingCategory?.let { cat ->
        EditCategoryDialog(
            category = cat,
            onDismiss = { editingCategory = null },
            onConfirm = { newName, newIcon ->
                onUpdateCategory(cat, newName, newIcon)
                editingCategory = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualInputForm(
    categories: List<Category>,
    onSave: (Double, Long?, String, String?, TransactionType) -> Unit,
    onOpenAddCategoryDialog: () -> Unit = {},
    initialCategory: Category? = null
) {
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var note by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }

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
                    onClick = onOpenAddCategoryDialog,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryGridItem(
    category: Category,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val emoji = CategoryIconMapper.getEmoji(category.icon)
    val bgColor = parseCategoryColor(category.color)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditCategoryDialog(
    category: Category,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(category.name) }
    var presetIcon by remember {
        mutableStateOf(
            if (CategoryIconMapper.isPresetIcon(category.icon)) category.icon
            else CategoryIconMapper.DEFAULT_ICON_KEY
        )
    }
    var customEmoji by remember {
        mutableStateOf(
            if (CategoryIconMapper.isPresetIcon(category.icon)) ""
            else category.icon
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑分类") },
        text = {
            Column {
                CategoryNameAndEmojiFields(
                    name = name,
                    onNameChange = { if (it.length <= 6) name = it },
                    customEmoji = customEmoji,
                    onCustomEmojiChange = { emoji ->
                        if (emoji.length <= 16) {
                            customEmoji = emoji
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                CategoryIconEditor(
                    presetIcon = presetIcon,
                    customEmoji = customEmoji,
                    onPresetIconSelected = { iconKey ->
                        presetIcon = iconKey
                        customEmoji = CategoryIconMapper.getEmoji(iconKey)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, resolveCategoryIcon(presetIcon, customEmoji))
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun Context.hasAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiActionButton(
    aiInput: String,
    isRecording: Boolean,
    isSubmitting: Boolean,
    voiceStatus: VoiceStatus,
    onSubmit: () -> Unit,
    onVoiceToggle: () -> Unit
) {
    val isProcessing = isSubmitting || voiceStatus is VoiceStatus.Processing

    // Keep references fresh for use inside pointerInput coroutine
    val currentOnVoiceToggle by rememberUpdatedState(onVoiceToggle)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentIsProcessing by rememberUpdatedState(isProcessing)
    val currentAiInput by rememberUpdatedState(aiInput)

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> MaterialTheme.colorScheme.error
            isProcessing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isRecording) buttonColor.copy(alpha = pulseAlpha) else buttonColor
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val upBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (upBeforeLongPress != null) {
                        // Short tap
                        when {
                            currentIsRecording -> currentOnVoiceToggle()
                            currentAiInput.isNotBlank() && !currentIsProcessing -> currentOnSubmit()
                            else -> {}
                        }
                    } else {
                        // Long press reached — start recording
                        if (!currentIsProcessing) currentOnVoiceToggle()
                        // Wait for finger release
                        waitForUpOrCancellation()
                        // Release — stop recording
                        if (currentIsRecording) currentOnVoiceToggle()
                    }
                }
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            when {
                isRecording -> {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "录音中",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🎙 录音中...松开结束", color = Color.White)
                }
                isSubmitting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("识别中...", color = Color.White)
                }
                voiceStatus is VoiceStatus.Processing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("语音识别中...", color = Color.White)
                }
                else -> {
                    Text(
                        "✨ AI 识别并记账",
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
    Text(
        text = "💡 长按按钮语音输入，点击提交 AI 识别",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
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
