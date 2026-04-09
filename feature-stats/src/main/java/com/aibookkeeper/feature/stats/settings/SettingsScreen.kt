package com.aibookkeeper.feature.stats.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.feature.stats.navigation.StatsRoutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    onNotificationServiceToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh permission state when the screen resumes (e.g. returning from system settings)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshState()
        }
    }

    // Permission launcher for requesting POST_NOTIFICATIONS
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
        if (granted) {
            onNotificationServiceToggle(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Notification section header ──────────────────────────
            Text(
                text = "通知设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // ── Permission status row ────────────────────────────────
            NotificationPermissionRow(
                isGranted = uiState.isPermissionGranted,
                onClick = {
                    if (!uiState.isPermissionGranted) {
                        if (NotificationPermissionHelper.needsRuntimePermission() &&
                            !NotificationPermissionHelper.hasRequestedBefore(context)
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Permission was denied permanently — open system settings
                            context.startActivity(
                                NotificationPermissionHelper.createNotificationSettingsIntent(context)
                            )
                        }
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Persistent notification toggle ───────────────────────
            NotificationToggleRow(
                isEnabled = uiState.isNotificationEnabled,
                isPermissionGranted = uiState.isPermissionGranted,
                onToggle = { enabled ->
                    if (!uiState.isPermissionGranted && enabled) {
                        // Need permission first
                        if (NotificationPermissionHelper.needsRuntimePermission() &&
                            !NotificationPermissionHelper.hasRequestedBefore(context)
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                NotificationPermissionHelper.createNotificationSettingsIntent(context)
                            )
                        }
                    } else {
                        viewModel.setNotificationEnabled(enabled)
                        onNotificationServiceToggle(enabled)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "智能记账",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Accessibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = "页面监听记账",
                subtitle = if (uiState.isAccessibilityServiceActive) {
                    "已启用 · 自动监听支付页面"
                } else {
                    "未启用 · 需要开启无障碍权限"
                },
                checked = uiState.isAccessibilityMonitoringEnabled,
                onCheckedChange = { enabled ->
                    viewModel.setAccessibilityMonitoringEnabled(enabled)
                    if (enabled && !uiState.isAccessibilityServiceActive) {
                        viewModel.openAccessibilitySettings(context)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            if (uiState.isAccessibilityMonitoringEnabled) {
                SettingsNavigationRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = "支付页面规则",
                    subtitle = "管理微信、支付宝等支付页面检测规则",
                    onClick = { navController.navigate(StatsRoutes.PAYMENT_PATTERNS) }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Azure OpenAI section ─────────────────────────────────
            Text(
                text = "AI 设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Azure OpenAI 配置",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Text(
                text = "配置后支持自然语言智能记账，不配置则使用本地规则解析",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = "优先使用系统语音识别",
                subtitle = if (uiState.preferLocalSpeech) {
                    "检测到系统语音可用时优先使用它，不可用时再回退到 Azure 云端"
                } else {
                    "优先使用 Azure 云端语音；当云端不可用时仍会使用系统语音"
                },
                checked = uiState.preferLocalSpeech,
                onCheckedChange = viewModel::setPreferLocalSpeech
            )

            Text(
                text = if (uiState.isSystemSpeechAvailable) {
                    "✅ ${uiState.systemSpeechSummary}"
                } else {
                    "⚠️ ${uiState.systemSpeechSummary}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.isSystemSpeechAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (uiState.systemSpeechProvider.isNotBlank()) {
                Text(
                    text = "当前系统语音服务：${uiState.systemSpeechProvider}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsNavigationRow(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = "本地语音诊断",
                subtitle = "检测系统语音识别 Activity / Service、权限与回调日志",
                onClick = { navController.navigate(StatsRoutes.LOCAL_SPEECH_DIAGNOSTIC) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            OutlinedTextField(
                value = uiState.azureEndpoint,
                onValueChange = { viewModel.setAzureEndpoint(it) },
                label = { Text("Endpoint") },
                placeholder = { Text("https://xxx.openai.azure.com/") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = uiState.azureApiKey,
                onValueChange = { viewModel.setAzureApiKey(it) },
                label = { Text("API Key") },
                placeholder = { Text("输入你的 Azure OpenAI Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = uiState.azureDeployment,
                onValueChange = { viewModel.setAzureDeployment(it) },
                label = { Text("Deployment") },
                placeholder = { Text("gpt-4.1-mini") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = uiState.azureSpeechDeployment,
                onValueChange = { viewModel.setAzureSpeechDeployment(it) },
                label = { Text("语音 Deployment") },
                placeholder = { Text("gpt-4o-mini-transcribe / whisper") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (uiState.azureApiKey.isNotBlank() && uiState.azureEndpoint.isNotBlank())
                    "✅ 文本 AI 已配置" else "⚠️ 文本 AI 未配置 — 将使用本地规则解析",
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.azureApiKey.isNotBlank() && uiState.azureEndpoint.isNotBlank())
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Text(
                text = if (
                    uiState.azureApiKey.isNotBlank() &&
                    uiState.azureEndpoint.isNotBlank() &&
                    uiState.azureSpeechDeployment.isNotBlank()
                ) {
                    "✅ 云端语音已配置，可作为系统语音不可用时的兜底"
                } else if (uiState.isSystemSpeechAvailable) {
                    "ℹ️ 云端语音未配置 — 当前仍可使用系统语音识别"
                } else {
                    "⚠️ 云端语音未配置，且当前未检测到系统语音入口"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    uiState.azureApiKey.isNotBlank() &&
                    uiState.azureEndpoint.isNotBlank() &&
                    uiState.azureSpeechDeployment.isNotBlank()
                ) {
                    MaterialTheme.colorScheme.primary
                } else if (uiState.isSystemSpeechAvailable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "关于",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsNavigationRow(
                icon = {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = "更新日志",
                subtitle = "查看版本更新历史",
                onClick = { navController.navigate(StatsRoutes.CHANGELOG) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Version info (dynamic from PackageManager)
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }

            Text(
                text = "v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────

@Composable
private fun NotificationPermissionRow(
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.Notifications
            else Icons.Default.NotificationsOff,
            contentDescription = null,
            tint = if (isGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "通知权限",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (isGranted) "已授权" else "未授权 — 点击前往设置",
                style = MaterialTheme.typography.bodySmall,
                color = if (isGranted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
        if (!isGranted) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "前往设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationToggleRow(
    isEnabled: Boolean,
    isPermissionGranted: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "常驻通知栏",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "在通知栏显示快捷记账入口",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = isEnabled && isPermissionGranted,
            onCheckedChange = { onToggle(it) }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = "进入$title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(navController = rememberNavController())
    }
}
