package com.aibookkeeper.feature.capture.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors
import com.aibookkeeper.core.data.ai.ExtractionCategoryProvider
import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.ai.NotificationExtractionPipeline
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val entryPoint = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, CaptureScreenEntryPoint::class.java)
    }
    val pipeline = remember(entryPoint) { entryPoint.notificationExtractionPipeline() }
    val transactionRepository = remember(entryPoint) { entryPoint.transactionRepository() }
    val strategyManager = remember(entryPoint) { entryPoint.extractionStrategyManager() }
    val categoryProvider = remember(entryPoint) { entryPoint.extractionCategoryProvider() }

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var ocrText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var processingLabel by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }
    var savedMessage by remember { mutableStateOf("") }
    var cameraImageFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showFullscreenEditor by remember { mutableStateOf(false) }
    var showResultPage by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var pendingImageAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        onDispose { cameraImageFile?.delete() }
    }

    val hasContent = ocrText.isNotBlank() || extractionResult != null || savedMessage.isNotBlank()

    fun clearAll() {
        imageUri = null
        pendingCameraUri = null
        ocrText = ""
        errorMessage = ""
        extractionResult = null
        savedMessage = ""
        processingLabel = ""
        cameraImageFile?.delete()
        cameraImageFile = null
    }

    // Wrap image-switching actions: confirm if content exists
    fun switchImageWithConfirm(action: () -> Unit) {
        if (hasContent) {
            pendingImageAction = action
            showClearConfirmDialog = true
        } else {
            action()
        }
    }

    val onImageSelected: (Uri) -> Unit = { uri ->
        pendingCameraUri = null
        ocrText = ""
        errorMessage = ""
        extractionResult = null
        savedMessage = ""
        processingLabel = ""
        showResultPage = false
        imageUri = uri
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri
        pendingCameraUri = null
        if (success && capturedUri != null) {
            onImageSelected(capturedUri)
        } else {
            cameraImageFile?.delete()
            cameraImageFile = null
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            cameraImageFile?.delete()
            cameraImageFile = null
            onImageSelected(uri)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            cameraImageFile?.delete()
            cameraImageFile = null
            onImageSelected(uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCameraUri?.let(cameraLauncher::launch)
        } else {
            cameraImageFile?.delete()
            cameraImageFile = null
            pendingCameraUri = null
            Toast.makeText(context, "需要相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    fun doLaunchCamera() {
        val imageFile = runCatching {
            File.createTempFile("capture_", ".jpg", context.cacheDir)
        }.getOrElse {
            Toast.makeText(context, "无法创建临时图片", Toast.LENGTH_SHORT).show()
            return
        }
        val captureUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        }.getOrElse {
            imageFile.delete()
            Toast.makeText(context, "无法启动相机", Toast.LENGTH_SHORT).show()
            return
        }
        cameraImageFile = imageFile
        pendingCameraUri = captureUri
        if (context.hasCameraPermission()) {
            cameraLauncher.launch(captureUri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun launchCamera() = switchImageWithConfirm {
        clearAll()
        doLaunchCamera()
    }

    fun launchGallery() = switchImageWithConfirm {
        clearAll()
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchFile() = switchImageWithConfirm {
        clearAll()
        fileLauncher.launch("image/*")
    }

    fun runOcrOnly(uri: Uri) {
        isProcessing = true
        processingLabel = "正在 OCR 识别文字..."
        errorMessage = ""
        extractionResult = null
        savedMessage = ""

        val inputImage = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse {
            isProcessing = false
            errorMessage = "无法读取图片: ${it.message ?: "未知错误"}"
            return
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                isProcessing = false
                ocrText = visionText.text.trim()
                if (ocrText.isBlank()) errorMessage = "未识别到文字内容"
            }
            .addOnFailureListener { e ->
                isProcessing = false
                errorMessage = "OCR识别失败: ${e.message ?: "未知错误"}"
            }
            .addOnCompleteListener { recognizer.close() }
    }

    fun runAiFromImage(uri: Uri) {
        if (!strategyManager.isAiConfigured) {
            errorMessage = "尚未配置 AI 服务，请前往「设置 → Azure OpenAI 配置」中设置 Endpoint 和 Key"
            return
        }
        isProcessing = true
        processingLabel = "AI 正在分析图片..."
        errorMessage = ""
        extractionResult = null
        savedMessage = ""

        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取图片")
                    val bytes = inputStream.use { it.readBytes() }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val categoryNames = categoryProvider.getCategoryNames(emptyList())
                    strategyManager.extractFromImage(base64, mime, categoryNames)
                }
            }.onSuccess { result ->
                isProcessing = false
                if (result.isSuccess) extractionResult = result.getOrNull()
                else errorMessage = "AI 识别失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
            }.onFailure { throwable ->
                isProcessing = false
                errorMessage = throwable.message ?: "识别失败，请重试"
            }
        }
    }

    // Convert OCR text → AI extraction (the arrow button)
    fun runAiFromText(text: String) {
        if (text.isBlank()) {
            errorMessage = "请先进行 OCR 识别"
            return
        }
        if (!strategyManager.isAiConfigured) {
            errorMessage = "尚未配置 AI 服务，请前往「设置 → Azure OpenAI 配置」中设置 Endpoint 和 Key"
            return
        }
        isProcessing = true
        processingLabel = "AI 正在提取消费信息..."
        errorMessage = ""
        extractionResult = null
        savedMessage = ""

        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val categoryNames = categoryProvider.getCategoryNames(emptyList())
                    strategyManager.extractFromOcr(text, categoryNames)
                }
            }.onSuccess { result ->
                isProcessing = false
                if (result.isSuccess) extractionResult = result.getOrNull()
                else errorMessage = "AI 提取失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
            }.onFailure { throwable ->
                isProcessing = false
                errorMessage = throwable.message ?: "提取失败，请重试"
            }
        }
    }

    fun confirmAndSave(data: ExtractionResult) {
        isProcessing = true
        processingLabel = "正在保存..."
        val sourceText = ocrText.ifBlank { "AI Vision: image" }

        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pipeline.processNotification("OCR", sourceText)
                }
            }.onSuccess { transactionId ->
                isProcessing = false
                if (transactionId > 0) {
                    savedMessage = "✅ 记账成功 ¥${"%.2f".format(data.amount ?: 0.0)} ${data.category}"
                    extractionResult = null
                } else {
                    errorMessage = "保存失败，请重试"
                }
            }.onFailure { throwable ->
                isProcessing = false
                errorMessage = "保存失败: ${throwable.message ?: "未知错误"}"
            }
        }
    }

    // Top ← goes back to AI BottomSheet
    fun navigateBackToAiSheet() {
        navController.previousBackStackEntry?.savedStateHandle?.set("openAiSheet", true)
        navController.popBackStack()
    }

    // Success "返回" goes to Home directly
    fun navigateBackToHome() {
        navController.popBackStack()
    }

    @Composable
    fun ProcessingStatusCard() {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(processingLabel, style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    fun ErrorMessageCard(message: String) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    @Composable
    fun ImageSourceButtons(rowModifier: Modifier = Modifier) {
        Row(
            modifier = rowModifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { launchCamera() },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("📷 拍照")
            }
            OutlinedButton(
                onClick = { launchGallery() },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("🖼️ 相册")
            }
            OutlinedButton(
                onClick = { launchFile() },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("📁 文件")
            }
        }
    }

    // ── Confirm dialog when switching images ──
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showClearConfirmDialog = false
                pendingImageAction = null
            },
            title = { Text("更换图片") },
            text = { Text("已有的识别内容将被清除，确定要更换图片吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmDialog = false
                    pendingImageAction?.invoke()
                    pendingImageAction = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearConfirmDialog = false
                    pendingImageAction = null
                }) { Text("取消") }
            }
        )
    }

    // ── Fullscreen text editor ──
    if (showFullscreenEditor) {
        Dialog(
            onDismissRequest = { showFullscreenEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("编辑识别文本") },
                        navigationIcon = {
                            IconButton(onClick = { showFullscreenEditor = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                            }
                        },
                        actions = {
                            TextButton(onClick = { showFullscreenEditor = false }) {
                                Text("完成")
                            }
                        }
                    )
                }
            ) { padding ->
                OutlinedTextField(
                    value = ocrText,
                    onValueChange = {
                        ocrText = it
                        extractionResult = null
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    placeholder = { Text("OCR 识别结果") }
                )
            }
        }
    }

    if (showResultPage) {
        Dialog(
            onDismissRequest = { showResultPage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("识别结果") },
                        navigationIcon = {
                            IconButton(onClick = { showResultPage = false }) {
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isProcessing) {
                            ProcessingStatusCard()
                        }

                        if (errorMessage.isNotBlank()) {
                            ErrorMessageCard(errorMessage)
                        }

                        // Side-by-side: labels on top, boxes below, buttons at bottom
                        // Determine left label based on how we got here
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Left label
                            Text(
                                text = "📝 识别结果",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            // Right label
                            Text(
                                text = "🤖 AI 结果",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Two aligned boxes
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Left: editable OCR text
                            OutlinedTextField(
                                value = ocrText,
                                onValueChange = {
                                    ocrText = it
                                    extractionResult = null
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(240.dp),
                                placeholder = { Text("识别结果将显示在这里\n可编辑文本内容", style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(12.dp)
                            )

                            // Right: AI result
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(240.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (extractionResult != null) {
                                        val data = extractionResult!!
                                        Text(
                                            text = "¥${"%.2f".format(data.amount ?: 0.0)}",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "${if (data.type == "EXPENSE") "支出" else "收入"} · ${data.category}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        if (!data.merchantName.isNullOrBlank()) {
                                            Text(
                                                text = "🏪 ${data.merchantName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                        if (!data.note.isNullOrBlank()) {
                                            Text(
                                                text = "📝 ${data.note}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                        Text(
                                            text = "置信度 ${"%.0f".format(data.confidence * 100)}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "等待 AI 分析...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom row: [全屏编辑] [AI提取→] [✨ AI记账]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { showFullscreenEditor = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("全屏编辑", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { runAiFromText(ocrText) },
                                enabled = ocrText.isNotBlank() && !isProcessing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("AI提取 →", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { extractionResult?.let { confirmAndSave(it) } },
                                enabled = extractionResult != null && !isProcessing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("✨ AI记账", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (savedMessage.isNotBlank()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = savedMessage,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { clearAll(); showResultPage = false },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("继续识别")
                                        }
                                        OutlinedButton(
                                            onClick = { navigateBackToHome() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("返回首页")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ImageSourceButtons(
                        rowModifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // ── Main UI ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍照识别") },
                navigationIcon = {
                    IconButton(onClick = { navigateBackToAiSheet() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (imageUri != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        AsyncImage(
                            model = imageUri,
                            contentDescription = "待识别图片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = "📸", style = MaterialTheme.typography.displaySmall)
                            Text(
                                text = "拍摄小票、外卖截图或支付截图",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "可选择 OCR 识别，或交给 AI 提取消费信息",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    ImageSourceButtons()
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (imageUri != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                imageUri?.let {
                                    showResultPage = true
                                    runOcrOnly(it)
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("📝 OCR识别")
                        }
                        Button(
                            onClick = {
                                imageUri?.let {
                                    if (!strategyManager.isAiConfigured) {
                                        errorMessage = "尚未配置 AI 服务，请前往「设置 → Azure OpenAI 配置」中设置 Endpoint 和 Key"
                                    } else {
                                        showResultPage = true
                                        runAiFromImage(it)
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("🤖 AI识别")
                        }
                    }
                }

                if (errorMessage.isNotBlank()) {
                    ErrorMessageCard(errorMessage)
                }
            }

            if (imageUri != null) {
                ImageSourceButtons(
                    rowModifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureScreenEntryPoint {
    fun notificationExtractionPipeline(): NotificationExtractionPipeline
    fun transactionRepository(): TransactionRepository
    fun extractionStrategyManager(): ExtractionStrategyManager
    fun extractionCategoryProvider(): ExtractionCategoryProvider
}
