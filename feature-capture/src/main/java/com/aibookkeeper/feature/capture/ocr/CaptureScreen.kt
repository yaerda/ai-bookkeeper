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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    DisposableEffect(Unit) {
        onDispose {
            cameraImageFile?.delete()
        }
    }

    fun clearResultState() {
        ocrText = ""
        errorMessage = ""
        extractionResult = null
        savedMessage = ""
        processingLabel = ""
    }

    fun clearSelectedImage() {
        imageUri = null
        pendingCameraUri = null
        clearResultState()
        cameraImageFile?.delete()
        cameraImageFile = null
    }

    val onImageSelected: (Uri) -> Unit = { uri ->
        pendingCameraUri = null
        clearResultState()
        imageUri = uri
        // No auto-processing — user chooses when to analyze
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
            if (!success) {
                Toast.makeText(context, "已取消拍照", Toast.LENGTH_SHORT).show()
            }
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

    fun launchCameraCapture() {
        clearSelectedImage()
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

    // OCR only — shows text, no extraction
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

    // AI extraction — OCR first, then AI extracts structured data (no auto-save)
    fun runAiExtraction(uri: Uri) {
        isProcessing = true
        processingLabel = "正在 OCR 识别..."
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
                val text = visionText.text.trim()
                ocrText = text
                if (text.isBlank()) {
                    isProcessing = false
                    errorMessage = "未识别到文字内容"
                    return@addOnSuccessListener
                }

                processingLabel = "AI 正在提取消费信息..."
                coroutineScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val categoryNames = categoryProvider.getCategoryNames(emptyList())
                            strategyManager.extractFromOcr(text, categoryNames)
                        }
                    }.onSuccess { result ->
                        isProcessing = false
                        if (result.isSuccess) {
                            extractionResult = result.getOrNull()
                        } else {
                            errorMessage = "AI 提取失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                        }
                    }.onFailure { throwable ->
                        isProcessing = false
                        errorMessage = throwable.message ?: "提取失败，请重试"
                    }
                }
            }
            .addOnFailureListener { e ->
                isProcessing = false
                errorMessage = "OCR识别失败: ${e.message ?: "未知错误"}"
            }
            .addOnCompleteListener { recognizer.close() }
    }

    // Re-extract from edited OCR text (no auto-save)
    fun reExtractFromText(text: String) {
        if (text.isBlank()) {
            errorMessage = "请先识别或输入文字内容"
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
                if (result.isSuccess) {
                    extractionResult = result.getOrNull()
                } else {
                    errorMessage = "AI 提取失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
            }.onFailure { throwable ->
                isProcessing = false
                errorMessage = throwable.message ?: "提取失败，请重试"
            }
        }
    }

    // Confirm and save
    fun confirmAndSave(data: ExtractionResult) {
        isProcessing = true
        processingLabel = "正在保存..."

        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pipeline.processNotification("OCR", ocrText)
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

    fun navigateBack() {
        navController.previousBackStackEntry?.savedStateHandle?.set("openAiSheet", true)
        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍照识别") },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Image preview
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageUri != null) {
                        AsyncImage(
                            model = imageUri,
                            contentDescription = "待识别图片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(
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
                                text = "选择图片后，可选择 OCR 或 AI 识别",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Action buttons after image selected (no auto-processing)
            if (imageUri != null && savedMessage.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { imageUri?.let { runOcrOnly(it) } },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("📝 OCR识别")
                    }
                    Button(
                        onClick = { imageUri?.let { runAiExtraction(it) } },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("🤖 AI识别")
                    }
                }
            }

            // Processing indicator
            if (isProcessing) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(processingLabel, fontWeight = FontWeight.Medium)
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Error
            if (errorMessage.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "识别失败",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // OCR text (editable)
            if (ocrText.isNotBlank() && savedMessage.isBlank()) {
                Text(
                    text = "识别文本（可编辑后重新提取）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = ocrText,
                    onValueChange = {
                        ocrText = it
                        errorMessage = ""
                        extractionResult = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    enabled = !isProcessing,
                    placeholder = { Text("OCR 识别结果") },
                    supportingText = { Text("如有识别错误，可手动修改后点击「重新提取」") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { reExtractFromText(ocrText) },
                        enabled = ocrText.isNotBlank() && !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🤖 重新提取")
                    }
                    OutlinedButton(
                        onClick = { clearSelectedImage() },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("换张图片")
                    }
                }
            }

            // Extraction result preview — user must confirm
            extractionResult?.let { data ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "📋 识别结果预览",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "金额：¥${"%.2f".format(data.amount ?: 0.0)}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "类型：${if (data.type == "EXPENSE") "支出" else "收入"}",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "分类：${data.category}",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (!data.merchantName.isNullOrBlank()) {
                            Text(
                                text = "商户：${data.merchantName}",
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (!data.note.isNullOrBlank()) {
                            Text(
                                text = "备注：${data.note}",
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = "置信度：${"%.0f".format(data.confidence * 100)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { confirmAndSave(data) },
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("✅ 确认记账")
                            }
                            OutlinedButton(
                                onClick = {
                                    extractionResult = null
                                    errorMessage = ""
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("❌ 放弃")
                            }
                        }
                    }
                }
            }

            // Success message
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
                                onClick = { clearSelectedImage() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("继续识别")
                            }
                            OutlinedButton(
                                onClick = { navigateBack() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("返回")
                            }
                        }
                    }
                }
            }

            // Image source buttons (always at bottom)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { launchCameraCapture() },
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("📷 拍照")
                }
                OutlinedButton(
                    onClick = {
                        clearSelectedImage()
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("🖼️ 相册")
                }
                OutlinedButton(
                    onClick = {
                        clearSelectedImage()
                        fileLauncher.launch("image/*")
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("📁 文件")
                }
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
