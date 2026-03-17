package com.aibookkeeper.feature.input.home

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.util.CategoryIconMapper
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.feature.input.navigation.InputRoutes
import com.aibookkeeper.core.common.extensions.toFriendlyDateString
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAiSheet by remember { mutableStateOf(false) }
    var aiInput by remember { mutableStateOf("") }
    var showPromptReview by remember { mutableStateOf(false) }
    var startWithVoice by remember { mutableStateOf(false) }

    // Auto-open AI sheet when returning from CaptureScreen
    LaunchedEffect(Unit) {
        val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
        savedStateHandle?.getStateFlow("openAiSheet", false)?.collect { shouldOpen ->
            if (shouldOpen) {
                showAiSheet = true
                savedStateHandle["openAiSheet"] = false
            }
        }
    }

    LaunchedEffect(uiState.voiceStatus) {
        when (val status = uiState.voiceStatus) {
            is VoiceStatus.Success -> {
                aiInput = if (aiInput.isBlank()) status.text else "$aiInput\n${status.text}"
                viewModel.resetVoiceStatus()
            }
            is VoiceStatus.Error -> {
                Toast.makeText(context, status.message, Toast.LENGTH_LONG).show()
                viewModel.resetVoiceStatus()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI 智能记账",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = LocalDate.now().toFriendlyDateString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "↑ 长按语音记账",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .combinedClickable(
                            onClick = {
                                showAiSheet = true
                            },
                            onLongClick = {
                                startWithVoice = true
                                showAiSheet = true
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "AI 记账",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (uiState.isLoading) {
            LoadingState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
            // Summary cards
            item {
                SummarySection(uiState = uiState)
            }

            // Recent transactions header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "最近记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "查看全部",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            navController.navigate("bills")
                        }
                    )
                }
            }

            if (uiState.recentTransactions.isEmpty() && !uiState.isLoading) {
                item {
                    EmptyRecentState()
                }
            } else {
                items(
                    items = uiState.recentTransactions.take(10),
                    key = { it.id }
                ) { transaction ->
                    RecentTransactionItem(
                        transaction = transaction,
                        onClick = { navController.navigate("transaction/${transaction.id}") }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        }
    }

    // AI 记账 BottomSheet
    if (showAiSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var pendingVoiceRequest by remember { mutableStateOf(false) }
        var isRecording by remember { mutableStateOf(false) }
        var isImageOcrProcessing by remember { mutableStateOf(false) }

        // Local SpeechRecognizer (no system UI)
        val speechRecognizer = remember {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        DisposableEffect(Unit) {
            onDispose {
                speechRecognizer.destroy()
            }
        }

        remember(speechRecognizer) {
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    isRecording = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        aiInput = if (aiInput.isBlank()) text else "$aiInput\n$text"
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
            true
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
        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                isImageOcrProcessing = true
                viewModel.resetAiStatus()
                Toast.makeText(context, "图片已选择，正在识别...", Toast.LENGTH_SHORT).show()
                runImageOcr(
                    context = context,
                    uri = uri,
                    onSuccess = { text ->
                        isImageOcrProcessing = false
                        aiInput = if (aiInput.isBlank()) text else "$aiInput\n$text"
                        Toast.makeText(context, "已提取图片文字，可继续 AI 识别", Toast.LENGTH_SHORT).show()
                    },
                    onError = { message ->
                        isImageOcrProcessing = false
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
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

        // Auto-start voice recording if opened via long-press
        if (startWithVoice) {
            LaunchedEffect(Unit) {
                startWithVoice = false
                kotlinx.coroutines.delay(600)
                startVoiceWithPermissionGuard()
            }
        }

        ModalBottomSheet(
            onDismissRequest = {
                showPromptReview = false
                showAiSheet = false
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "✨ AI 智能记账",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = aiInput,
                    onValueChange = { aiInput = it },
                    placeholder = { Text("每行一笔，如：\n买芒果28块\n打车15元") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 5,
                    minLines = 3,
                    trailingIcon = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                modifier = Modifier.size(40.dp),
                                onClick = {
                                    showPromptReview = false
                                    showAiSheet = false
                                    navController.navigate("capture/camera")
                                }
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "拍照记账",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                modifier = Modifier.size(40.dp),
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = "上传文件",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )

                TextButton(
                    onClick = { showPromptReview = !showPromptReview },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(if (showPromptReview) "收起 AI Prompt Review" else "Review / 修改 AI Prompt")
                }

                AnimatedVisibility(visible = showPromptReview) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "系统 Prompt（只读）",
                            style = MaterialTheme.typography.labelLarge
                        )
                        OutlinedTextField(
                            value = uiState.cloudSystemPrompt,
                            onValueChange = {},
                            readOnly = true,
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 8.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Text(
                            text = "用户自定义 Prompt",
                            style = MaterialTheme.typography.labelLarge
                        )
                        OutlinedTextField(
                            value = uiState.customCloudPrompt,
                            onValueChange = viewModel::setCustomCloudPrompt,
                            placeholder = { Text("例如：茶叶优先归到饮料，备注保留品牌") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                when {
                    isImageOcrProcessing -> {
                        Text(
                            text = "🖼️ 正在识别图片文字...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }

                // Unified AI button: tap = submit, long-press = voice input
                AiActionButton(
                    aiInput = aiInput,
                    isRecording = isRecording,
                    aiStatus = uiState.aiStatus,
                    voiceStatus = uiState.voiceStatus,
                    onSubmit = { viewModel.submitAiInput(aiInput) },
                    onVoiceToggle = {
                        if (uiState.voiceStatus is VoiceStatus.Processing) {
                            Toast.makeText(context, "正在识别中，请稍候", Toast.LENGTH_SHORT).show()
                        } else {
                            startVoiceWithPermissionGuard()
                        }
                    }
                )

                // Show result
                when (val status = uiState.aiStatus) {
                    is AiStatus.Success -> {
                        Text(
                            text = "✅ 记账成功：${status.message}",
                            color = Color(0xFF4CAF50),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        // Auto close after success
                        LaunchedEffect(status) {
                            kotlinx.coroutines.delay(1500)
                            aiInput = ""
                            viewModel.resetAiStatus()
                            showAiSheet = false
                        }
                    }
                    is AiStatus.Error -> {
                        Text(
                            text = "❌ ${status.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "加载中...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummarySection(uiState: HomeUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Today card
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "今日支出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "¥${"%.2f".format(uiState.todayExpense)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Month card
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "${uiState.currentMonth.monthValue}月支出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "¥${"%.2f".format(uiState.monthExpense)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (uiState.monthIncome > 0) {
                    Text(
                        text = "收入 ¥${"%.2f".format(uiState.monthIncome)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTransactionItem(transaction: Transaction, onClick: () -> Unit = {}) {
    val emoji = CategoryIconMapper.getEmoji(transaction.categoryIcon)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            val noteText = transaction.note
            val dateText = transaction.date.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
            val subtitle = if (!noteText.isNullOrBlank()) "$noteText · $dateText" else dateText
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Text(
            text = "${if (transaction.type == TransactionType.INCOME) "+" else "-"}${"%.2f".format(transaction.amount)}元",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (transaction.type == TransactionType.INCOME) Color(0xFF4CAF50)
            else MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

@Composable
private fun EmptyRecentState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "📝", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "还没有记录",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "试试输入「午饭35」开始记账",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun runImageOcr(
    context: Context,
    uri: Uri,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit
) {
    val inputImage = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse {
        onError("无法读取图片: ${it.message ?: "未知错误"}")
        return
    }
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    recognizer.process(inputImage)
        .addOnSuccessListener { visionText ->
            val text = visionText.text.trim()
            if (text.isBlank()) {
                onError("未识别到文字内容")
            } else {
                onSuccess(text)
            }
        }
        .addOnFailureListener { e ->
            onError("OCR识别失败: ${e.message ?: "未知错误"}")
        }
        .addOnCompleteListener {
            recognizer.close()
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

private fun Context.hasAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiActionButton(
    aiInput: String,
    isRecording: Boolean,
    aiStatus: AiStatus,
    voiceStatus: VoiceStatus,
    onSubmit: () -> Unit,
    onVoiceToggle: () -> Unit
) {
    val isProcessing = aiStatus is AiStatus.Processing || voiceStatus is VoiceStatus.Processing
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> MaterialTheme.colorScheme.error
            isProcessing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor"
    )

    // Pulsing animation when recording
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
            .combinedClickable(
                onClick = {
                    when {
                        isRecording -> onVoiceToggle() // stop recording
                        aiInput.isNotBlank() && !isProcessing -> onSubmit()
                        else -> {} // no-op when empty and not recording
                    }
                },
                onLongClick = {
                    if (!isProcessing) onVoiceToggle()
                }
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
                    Text("🎙 录音中...松开结束", color = Color.White)
                }
                aiStatus is AiStatus.Processing -> {
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
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(navController = rememberNavController())
    }
}
