package com.aibookkeeper.feature.input.text

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.aibookkeeper.feature.input.home.VoiceStatus
import com.aibookkeeper.feature.input.components.holdToTalkGesture
import com.aibookkeeper.feature.input.components.ProjectSelectionSection
import com.aibookkeeper.feature.input.components.rememberSpeechInputSession
import com.aibookkeeper.feature.input.components.SpeechPhase
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
    val ledgerState by viewModel.ledgerState.collectAsStateWithLifecycle()
    val projectState by viewModel.projectState.collectAsStateWithLifecycle()
    val selection = ledgerState.selection
    val initialSelection = remember { selection }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selection) {
        if (selection != initialSelection) viewModel.resetToIdle()
    }

    LaunchedEffect(uiState) {
        val success = uiState as? TextInputUiState.Success ?: return@LaunchedEffect
        Toast.makeText(
            context,
            "记账成功 ¥${"%.2f".format(success.amount)} ${success.category}",
            Toast.LENGTH_SHORT
        ).show()
        viewModel.resetToIdle()
    }

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
                    if (!ledgerState.canEdit) {
                        Text(ledgerState.errorMessage ?: if (ledgerState.isLoading) {
                            "账本分类正在加载，请稍候"
                        } else {
                            "你只有查看权限"
                        })
                    } else key(selection) {
                        AiInputSection(
                            navController = navController,
                            categories = categories,
                            initialCategoryId = initialCategoryId.takeIf { selection == initialSelection },
                            viewModel = viewModel,
                            onSubmitText = { viewModel.submitText(it, selection) },
                            projectState = projectState,
                            onManualSave = { amount, categoryId, categoryName, note, type, projectIds ->
                                viewModel.saveManual(
                                    amount,
                                    categoryId,
                                    categoryName,
                                    note,
                                    type,
                                    selection,
                                    projectIds
                                )
                            },
                            onAddCategory = { name, icon, type ->
                                viewModel.addCategory(name, icon, type, selection)
                            },
                            canUpdateCategories = ledgerState.canUpdateCategories,
                            onUpdateCategory = { cat, name, icon ->
                                viewModel.updateCategory(cat, name, icon, selection)
                            }
                        )
                    }
                }
                is TextInputUiState.Extracting -> {
                    ExtractingSection()
                }
                is TextInputUiState.Preview -> {
                    val preview = uiState as TextInputUiState.Preview
                    key(selection) {
                        PreviewSection(
                            preview = preview,
                            projectState = projectState,
                            onConfirm = viewModel::confirmSave,
                            onRetry = viewModel::resetToIdle
                        )
                    }
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
    projectState: com.aibookkeeper.core.data.model.ProjectLedgerState,
    onManualSave: (Double, Long?, String, String?, TransactionType, List<String>?) -> Unit,
    onAddCategory: (String, String, TransactionType) -> Unit = { _, _, _ -> },
    canUpdateCategories: Boolean = true,
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
    var newCategoryType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var manualProjectIds by remember {
        mutableStateOf<List<String>?>(null)
    }
    var pendingVoiceRequest by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()

    val speech = rememberSpeechInputSession(
        onText = { text -> inputText = if (inputText.isBlank()) text else "$inputText\n$text" }
    )
    val speechState by speech.state.collectAsStateWithLifecycle()
    val isRecording = speechState.isRecording

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
            speech.start()
        } else {
            pendingVoiceRequest = false
            Toast.makeText(context, "请授予麦克风权限后再使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoiceWithPermissionGuard() {
        if (context.hasAudioPermission()) {
            speech.start()
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

    speechState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (speechState.partialText.isNotBlank()) {
        Text(speechState.partialText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AiActionButton(
        aiInput = inputText,
        isRecording = isRecording,
        isSubmitting = false,
        voiceStatus = if (speechState.isProcessing) VoiceStatus.Processing else voiceStatus,
        recordingLabel = if (speechState.phase == SpeechPhase.STARTING) "正在打开麦克风…" else "麦克风已就绪，松开结束",
        onHoldReleased = speech::release,
        onHoldCancelled = speech::cancel,
        onSubmit = ::submitInput,
        onHoldStarted = {
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
    if (!canUpdateCategories) {
        Text(
            "分类与云端共享，可新增，暂不支持修改或删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
        val visibleCategories = categories.filter { it.type == TransactionType.EXPENSE }.take(8)
        visibleCategories.forEach { cat ->
            CategoryGridItem(
                category = cat,
                onClick = {
                    gridSelectedCategory = cat
                    showManualForm = true
                },
                onLongClick = { if (canUpdateCategories) editingCategory = cat },
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
            onClick = {
                newCategoryType = TransactionType.EXPENSE
                showAddCategoryDialog = true
            },
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
                        projectState = projectState,
                        selectedProjectIds = manualProjectIds,
                        onSelectedProjectIdsChange = { manualProjectIds = it },
                        onSave = { amount, catId, catName, note, type, projectIds ->
                            onManualSave(amount, catId, catName, note, type, projectIds)
                            showManualForm = false
                        },
                        onOpenAddCategoryDialog = {
                            newCategoryType = it
                            showAddCategoryDialog = true
                        },
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
            onNameChange = { if (it.length <= 100) newCategoryName = it },
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
                    resolveCategoryIcon(newCategoryPresetIcon, newCategoryCustomEmoji),
                    newCategoryType
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
    projectState: com.aibookkeeper.core.data.model.ProjectLedgerState,
    selectedProjectIds: List<String>?,
    onSelectedProjectIdsChange: (List<String>?) -> Unit,
    onSave: (Double, Long?, String, String?, TransactionType, List<String>?) -> Unit,
    onOpenAddCategoryDialog: (TransactionType) -> Unit = {},
    initialCategory: Category? = null
) {
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var note by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(initialCategory?.type != TransactionType.INCOME) }

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
                    onClick = { isExpense = true; selectedCategory = null },
                    label = { Text("支出") }
                )
                Spacer(modifier = Modifier.width(12.dp))
                FilterChip(
                    selected = !isExpense,
                    onClick = { isExpense = false; selectedCategory = null },
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
                modifier = Modifier.fillMaxWidth().testTag("manual-project-amount"),
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
                categories.filter {
                    it.type == if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
                }.forEach { cat ->
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
                    onClick = {
                        onOpenAddCategoryDialog(
                            if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
                        )
                    },
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

            ProjectSelectionSection(
                state = projectState,
                selectedProjectIds = selectedProjectIds,
                onSelectedProjectIdsChange = onSelectedProjectIdsChange,
                unspecifiedLabel = "保存时按当前默认项目"
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@Button
                    val catName = selectedCategory?.name ?: "其他"
                    val type = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
                    onSave(
                        amount,
                        selectedCategory?.id,
                        catName,
                        note.ifBlank { null },
                        type,
                        selectedProjectIds
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("manual-project-save"),
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
    projectState: com.aibookkeeper.core.data.model.ProjectLedgerState,
    onConfirm: (List<String>?) -> Unit,
    onRetry: () -> Unit
) {
    var previewProjectIds by remember {
        mutableStateOf<List<String>?>(null)
    }
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

    ProjectSelectionSection(
        state = projectState,
        selectedProjectIds = previewProjectIds,
        onSelectedProjectIdsChange = { previewProjectIds = it },
        unspecifiedLabel = "保存时按当前默认项目"
    )

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
            onClick = { onConfirm(previewProjectIds) },
            modifier = Modifier.weight(1f).testTag("preview-project-save"),
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
    recordingLabel: String,
    onHoldReleased: () -> Unit,
    onHoldCancelled: () -> Unit,
    onSubmit: () -> Unit,
    onHoldStarted: () -> Unit
) {
    val isProcessing = isSubmitting || voiceStatus is VoiceStatus.Processing

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
            .testTag("text-voice-submit")
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isRecording) buttonColor.copy(alpha = pulseAlpha) else buttonColor
            )
            .holdToTalkGesture(
                isRecording = isRecording,
                isProcessing = isProcessing,
                hasSubmitContent = aiInput.isNotBlank(),
                onHoldStarted = onHoldStarted,
                onHoldReleased = onHoldReleased,
                onHoldCancelled = onHoldCancelled,
                onSubmit = onSubmit
            )
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
                    Text(recordingLabel, color = Color.White)
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
