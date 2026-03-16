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
import com.aibookkeeper.core.data.ai.NotificationExtractionPipeline
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
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

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var ocrText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var extractedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var cameraImageFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraImageFile?.delete()
        }
    }

    fun clearResultState() {
        ocrText = ""
        resultMessage = ""
        errorMessage = ""
        extractedTransaction = null
    }

    fun clearSelectedImage() {
        imageUri = null
        pendingCameraUri = null
        clearResultState()
        cameraImageFile?.delete()
        cameraImageFile = null
    }

    val onImageReady: (Uri) -> Unit = { uri ->
        pendingCameraUri = null
        clearResultState()
        imageUri = uri
        processImage(
            context = context,
            uri = uri,
            onOcrResult = {
                ocrText = it
                errorMessage = ""
            },
            onTransactionResult = { transaction ->
                extractedTransaction = transaction
                resultMessage = transaction?.let {
                    "✅ 记账成功 ¥${"%.2f".format(it.amount)} ${it.categoryName ?: "未分类"}"
                } ?: "✅ 记账成功"
                errorMessage = ""
            },
            onError = {
                errorMessage = it
                resultMessage = ""
                extractedTransaction = null
            },
            setProcessing = { isProcessing = it },
            coroutineScope = coroutineScope,
            pipeline = pipeline,
            transactionRepository = transactionRepository
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri
        pendingCameraUri = null
        if (success && capturedUri != null) {
            onImageReady(capturedUri)
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
            onImageReady(uri)
        }
    }

    // File picker (broader than gallery — picks from Files, Downloads, etc.)
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            cameraImageFile?.delete()
            cameraImageFile = null
            onImageReady(uri)
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

    fun navigateToTextInput() {
        navController.previousBackStackEntry?.savedStateHandle?.set("openAiSheet", true)
        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍照识别") },
                navigationIcon = {
                    IconButton(onClick = { navigateToTextInput() }) {
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
                                text = "自动执行 ML Kit OCR，并交给 AI 提取消费信息",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

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
                        Column {
                            Text("识别中...", fontWeight = FontWeight.Medium)
                            Text(
                                "正在进行 OCR 与 AI 提取，请稍候",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

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
                        if (imageUri != null && !isProcessing) {
                            TextButton(onClick = { imageUri?.let(onImageReady) }) {
                                Text("重新识别")
                            }
                        }
                    }
                }
            }

            if (imageUri != null) {
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
                        resultMessage = ""
                        extractedTransaction = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    enabled = !isProcessing,
                    placeholder = { Text("这里会显示 OCR 识别结果") },
                    supportingText = { Text("如有识别错误，可手动修改后再次提取") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            clearResultState()
                            extractTransactionFromText(
                                text = ocrText,
                                coroutineScope = coroutineScope,
                                pipeline = pipeline,
                                transactionRepository = transactionRepository,
                                onTransactionResult = { transaction ->
                                    extractedTransaction = transaction
                                    resultMessage = transaction?.let {
                                        "✅ 记账成功 ¥${"%.2f".format(it.amount)} ${it.categoryName ?: "未分类"}"
                                    } ?: "✅ 记账成功"
                                },
                                onError = {
                                    errorMessage = it
                                    resultMessage = ""
                                    extractedTransaction = null
                                },
                                setProcessing = { isProcessing = it }
                            )
                        },
                        enabled = ocrText.isNotBlank() && !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重新提取")
                    }
                    OutlinedButton(
                        onClick = { clearSelectedImage() },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("继续拍照")
                    }
                }
            }

            if (resultMessage.isNotBlank()) {
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
                            text = resultMessage,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        extractedTransaction?.let { transaction ->
                            Text(
                                text = "金额：¥${"%.2f".format(transaction.amount)}",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "分类：${transaction.categoryName ?: "未分类"}",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (!transaction.merchantName.isNullOrBlank()) {
                                Text(
                                    text = "商户：${transaction.merchantName}",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (!transaction.note.isNullOrBlank()) {
                                Text(
                                    text = "备注：${transaction.note}",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        TextButton(onClick = { navigateToTextInput() }) {
                            Text("返回")
                        }
                    }
                }
            }

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

private fun processImage(
    context: Context,
    uri: Uri,
    onOcrResult: (String) -> Unit,
    onTransactionResult: (Transaction?) -> Unit,
    onError: (String) -> Unit,
    setProcessing: (Boolean) -> Unit,
    coroutineScope: CoroutineScope,
    pipeline: NotificationExtractionPipeline,
    transactionRepository: TransactionRepository
) {
    val inputImage = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse {
        setProcessing(false)
        onError("无法读取图片: ${it.message ?: "未知错误"}")
        return
    }

    setProcessing(true)
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    recognizer.process(inputImage)
        .addOnSuccessListener { visionText ->
            val text = visionText.text.trim()
            onOcrResult(text)

            if (text.isBlank()) {
                setProcessing(false)
                onError("未识别到文字内容")
                return@addOnSuccessListener
            }

            extractTransactionFromText(
                text = text,
                coroutineScope = coroutineScope,
                pipeline = pipeline,
                transactionRepository = transactionRepository,
                onTransactionResult = onTransactionResult,
                onError = onError,
                setProcessing = setProcessing
            )
        }
        .addOnFailureListener { e ->
            setProcessing(false)
            onError("OCR识别失败: ${e.message ?: "未知错误"}")
        }
        .addOnCompleteListener {
            recognizer.close()
        }
}

private fun extractTransactionFromText(
    text: String,
    coroutineScope: CoroutineScope,
    pipeline: NotificationExtractionPipeline,
    transactionRepository: TransactionRepository,
    onTransactionResult: (Transaction?) -> Unit,
    onError: (String) -> Unit,
    setProcessing: (Boolean) -> Unit
) {
    if (text.isBlank()) {
        onError("请先识别或输入文字内容")
        return
    }

    setProcessing(true)
    coroutineScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val transactionId = pipeline.processNotification("OCR", text)
                transactionId to if (transactionId > 0) transactionRepository.getById(transactionId) else null
            }
        }.onSuccess { (transactionId, transaction) ->
            setProcessing(false)
            if (transactionId > 0) {
                onTransactionResult(transaction)
            } else {
                onError("未识别到有效消费信息，请确认文字内容后重试")
            }
        }.onFailure { throwable ->
            setProcessing(false)
            onError(throwable.message ?: "提取失败，请重试")
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
}
