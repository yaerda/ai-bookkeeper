package com.aibookkeeper.feature.input.home

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.aibookkeeper.feature.input.navigation.InputRoutes
import kotlinx.coroutines.withTimeoutOrNull
import com.aibookkeeper.core.common.extensions.toFriendlyDateString
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAiSheet by remember { mutableStateOf(false) }
    var aiInput by remember { mutableStateOf("") }

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
            // Alternate icon between Add and Mic every second
            var showMic by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    showMic = !showMic
                }
            }
            FloatingActionButton(
                onClick = { showAiSheet = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = showMic,
                    animationSpec = tween(400),
                    label = "fabIcon"
                ) { isMic ->
                    if (isMic) {
                        Icon(Icons.Default.Mic, contentDescription = "语音记账")
                    } else {
                        Icon(Icons.Default.Add, contentDescription = "AI 记账")
                    }
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
        val context = LocalContext.current

        // Speech recognizer state
        var isRecording by remember { mutableStateOf(false) }
        var isSpeechProcessing by remember { mutableStateOf(false) }
        var hasPermission by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted
            if (!granted) {
                Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
            }
        }

        // In-app SpeechRecognizer (no system dialog)
        val speechRecognizer = remember {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        DisposableEffect(Unit) {
            onDispose {
                try {
                    speechRecognizer.cancel()
                    speechRecognizer.destroy()
                } catch (_: Exception) {}
            }
        }

        // Set up recognition listener
        val recognitionListener = remember {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isSpeechProcessing = true
                    isRecording = false
                }
                override fun onError(error: Int) {
                    isRecording = false
                    isSpeechProcessing = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
                        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，请检查网络"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                        else -> "语音识别失败(错误码:$error)"
                    }
                    Toast.makeText(context, "🎙 $msg", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    isSpeechProcessing = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        aiInput = if (aiInput.isBlank()) text else "${aiInput}\n${text}"
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
        }

        LaunchedEffect(speechRecognizer) {
            speechRecognizer.setRecognitionListener(recognitionListener)
        }

        fun startListening() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            speechRecognizer.startListening(intent)
            isRecording = true
        }

        fun stopListening() {
            speechRecognizer.stopListening()
        }

        ModalBottomSheet(
            onDismissRequest = {
                if (isRecording) {
                    speechRecognizer.cancel()
                    isRecording = false
                }
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
                    minLines = 3
                )

                // Action buttons row: camera, upload (mic removed — merged into AI button)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = {
                        Toast.makeText(context, "📷 拍照记账 · 敬请期待", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "拍照", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        Toast.makeText(context, "📁 导入账单 · 敬请期待", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.UploadFile, contentDescription = "上传", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                // Unified AI button: tap = submit, long-press = voice input
                AiActionButton(
                    isRecording = isRecording,
                    isSpeechProcessing = isSpeechProcessing,
                    isAiProcessing = uiState.aiStatus is AiStatus.Processing,
                    hasInput = aiInput.isNotBlank(),
                    onTap = {
                        if (aiInput.isNotBlank()) {
                            viewModel.submitAiInput(aiInput)
                        }
                    },
                    onLongPressStart = {
                        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                            Toast.makeText(context, "🎙 设备不支持语音识别", Toast.LENGTH_SHORT).show()
                            return@AiActionButton
                        }
                        val perm = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@AiActionButton
                        }
                        startListening()
                    },
                    onLongPressRelease = {
                        if (isRecording) {
                            stopListening()
                        }
                    }
                )

                // Hint text
                Text(
                    text = "💡 长按按钮语音输入，点击提交 AI 识别",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center
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
            val dateText = transaction.date.format(DateTimeFormatter.ofPattern("M/d"))
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

@Composable
private fun AiActionButton(
    isRecording: Boolean,
    isSpeechProcessing: Boolean,
    isAiProcessing: Boolean,
    hasInput: Boolean,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressRelease: () -> Unit
) {
    val isProcessing = isAiProcessing || isSpeechProcessing

    // Animate button color between states
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFE53935)
            isProcessing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )

    // Pulse animation during recording
    val pulseScale = if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        scale
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pulseScale)
            .clip(RoundedCornerShape(12.dp))
            .background(buttonColor)
            .pointerInput(isProcessing) {
                if (isProcessing) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    // Wait to see if it's a long press or a tap
                    val upOrTimeout = withTimeoutOrNull(longPressTimeout) {
                        waitForUpOrCancellation()
                    }
                    if (upOrTimeout != null) {
                        // Finger lifted before long-press threshold → it's a tap
                        onTap()
                    } else {
                        // Long-press triggered
                        onLongPressStart()
                        // Now wait for the finger to lift
                        waitForUpOrCancellation()
                        onLongPressRelease()
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
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "正在录音... 松开结束",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                isSpeechProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "语音识别中...",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                isAiProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI 识别中...",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                else -> {
                    Text(
                        text = "✨ AI 识别并记账",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
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
private fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(navController = rememberNavController())
    }
}
