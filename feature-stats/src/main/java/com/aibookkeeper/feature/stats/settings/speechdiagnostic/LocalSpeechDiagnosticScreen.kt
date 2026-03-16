package com.aibookkeeper.feature.stats.settings.speechdiagnostic

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.foundation.text.selection.SelectionContainer

private sealed interface PendingRecognizerAction {
    data object Default : PendingRecognizerAction
    data object OfflinePreferred : PendingRecognizerAction
    data object OnDevice : PendingRecognizerAction
    data class Explicit(val service: SpeechComponentInfo) : PendingRecognizerAction
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocalSpeechDiagnosticScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: LocalSpeechDiagnosticViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingRecognizerAction by remember { mutableStateOf<PendingRecognizerAction?>(null) }

    val speechIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleRecognizerIntentResult(result.resultCode, result.data)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onAudioPermissionResult(granted)
        val pending = pendingRecognizerAction
        pendingRecognizerAction = null
        if (granted && pending != null) {
            pending.dispatch(viewModel)
        }
    }

    fun launchRecognizerIntent(preferOffline: Boolean) {
        val intent = viewModel.prepareRecognizerIntent(preferOffline)
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            viewModel.onRecognizerIntentUnavailable(preferOffline)
            return
        }
        speechIntentLauncher.launch(intent)
    }

    fun runWithAudioPermission(action: PendingRecognizerAction) {
        if (context.hasRecordAudioPermission()) {
            action.dispatch(viewModel)
        } else {
            pendingRecognizerAction = action
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地语音诊断") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DiagnosticSectionCard(
                    title = "使用说明",
                    subtitle = "这个页面只走 Android 本地 SpeechRecognizer / RecognizerIntent，不走 Azure，不会保存音频文件。"
                ) {
                    Text(
                        text = "目标是分辨：没有公开入口、没有默认服务、只有 OEM 私有服务、还是语言/模型不支持。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "测试参数",
                    subtitle = "默认会使用自由说话模型，你可以改语言标签后重复跑同一组分支。"
                ) {
                    OutlinedTextField(
                        value = uiState.languageTag,
                        onValueChange = viewModel::setLanguageTag,
                        label = { Text("语言标签") },
                        placeholder = { Text("zh-CN / en-US") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "环境动作",
                    subtitle = "建议先刷新环境，再复制报告留档。"
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = viewModel::refreshEnvironment) {
                            Text("刷新环境")
                        }
                        OutlinedButton(
                            onClick = {
                                context.copyDiagnosticText(uiState.asClipboardText())
                                Toast.makeText(context, "诊断报告已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("复制报告")
                        }
                        TextButton(onClick = viewModel::clearLogs) {
                            Text("清空日志")
                        }
                    }
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "主动分支测试",
                    subtitle = "RecognizerIntent 不要求本 App 先拿到 RECORD_AUDIO；程序化 SpeechRecognizer 会先检查权限。"
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { launchRecognizerIntent(false) }) {
                            Text("启动 RecognizerIntent")
                        }
                        OutlinedButton(onClick = { launchRecognizerIntent(true) }) {
                            Text("Intent 优先离线")
                        }
                        OutlinedButton(onClick = { runWithAudioPermission(PendingRecognizerAction.Default) }) {
                            Text("默认 SpeechRecognizer")
                        }
                        OutlinedButton(onClick = { runWithAudioPermission(PendingRecognizerAction.OfflinePreferred) }) {
                            Text("默认+优先离线")
                        }
                        OutlinedButton(onClick = { runWithAudioPermission(PendingRecognizerAction.OnDevice) }) {
                            Text("On-device")
                        }
                        OutlinedButton(onClick = viewModel::stopListening, enabled = uiState.isListening) {
                            Text("stopListening")
                        }
                        OutlinedButton(onClick = viewModel::cancelListening, enabled = uiState.isListening) {
                            Text("cancelListening")
                        }
                    }
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "最近一次测试结果"
                ) {
                    KeyValueList(
                        values = listOf(
                            DiagnosticKeyValue("activeRecognizer", uiState.activeRecognizerLabel ?: "无"),
                            DiagnosticKeyValue("latestSupport", uiState.latestSupportSummary.ifBlank { "无" }),
                            DiagnosticKeyValue("latestIntentResult", uiState.latestIntentResult.ifBlank { "无" }),
                            DiagnosticKeyValue("latestPartial", uiState.latestPartialText.ifBlank { "无" }),
                            DiagnosticKeyValue("latestFinal", uiState.latestFinalText.ifBlank { "无" })
                        )
                    )
                }
            }

            item {
                DiagnosticSectionCard(title = "设备信息") {
                    KeyValueList(uiState.report.deviceInfo)
                }
            }

            item {
                DiagnosticSectionCard(title = "能力检查") {
                    KeyValueList(uiState.report.capabilityInfo)
                }
            }

            item {
                DiagnosticSectionCard(title = "Secure Settings 观察值") {
                    KeyValueList(uiState.report.secureSettings)
                }
            }

            item {
                DiagnosticSectionCard(title = "Assistant Role 观察值") {
                    KeyValueList(uiState.report.assistantRoleInfo)
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "RecognizerIntent Activities",
                    subtitle = "如果这里是 0，说明系统没有公开的语音面板入口给第三方 App 解析。"
                ) {
                    if (uiState.report.recognizerActivities.isEmpty()) {
                        Text("未发现可处理 ACTION_RECOGNIZE_SPEECH 的 Activity")
                    } else {
                        ComponentInfoList(uiState.report.recognizerActivities)
                    }
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "Recognition Services",
                    subtitle = "如果这里有 service，但默认 recognizer 仍不可用，通常意味着默认绑定、导出能力或 OEM 私有集成有问题。"
                ) {
                    if (uiState.report.recognitionServices.isEmpty()) {
                        Text("未发现可查询的 RecognitionService")
                    } else {
                        ComponentInfoList(
                            components = uiState.report.recognitionServices,
                            actionContent = { service ->
                                OutlinedButton(
                                    onClick = { runWithAudioPermission(PendingRecognizerAction.Explicit(service)) }
                                ) {
                                    Text("测试这个 service")
                                }
                            }
                        )
                    }
                }
            }

            item {
                DiagnosticSectionCard(
                    title = "Timeline Logs",
                    subtitle = "同时会写入 Logcat，Tag = LocalSpeechDiag。"
                ) {
                    if (uiState.logs.isEmpty()) {
                        Text("暂无日志")
                    } else {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                uiState.logs.forEach { entry ->
                                    Text(
                                        text = "${entry.timestamp} ${entry.level.name}: ${entry.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValueList(values: List<DiagnosticKeyValue>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEachIndexed { index, item ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                SelectionContainer {
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (index != values.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ComponentInfoList(
    components: List<SpeechComponentInfo>,
    actionContent: @Composable (SpeechComponentInfo) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        components.forEach { component ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(component.label.ifBlank { component.packageName })
                        }
                    )
                    SelectionContainer {
                        Text(
                            text = component.componentId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("exported=${component.exported}") }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("component=${component.componentEnabled}") }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("app=${component.appEnabled}") }
                        )
                    }
                    actionContent(component)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                content()
            }
        )
    }
}

private fun PendingRecognizerAction.dispatch(viewModel: LocalSpeechDiagnosticViewModel) {
    when (this) {
        PendingRecognizerAction.Default -> viewModel.startDefaultRecognizer()
        PendingRecognizerAction.OfflinePreferred -> viewModel.startOfflinePreferredRecognizer()
        PendingRecognizerAction.OnDevice -> viewModel.startOnDeviceRecognizer()
        is PendingRecognizerAction.Explicit -> viewModel.startExplicitServiceRecognizer(service)
    }
}

private fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.copyDiagnosticText(value: String) {
    val clipboardManager = getSystemService(ClipboardManager::class.java)
    clipboardManager?.setPrimaryClip(ClipData.newPlainText("local-speech-diagnostic", value))
}
