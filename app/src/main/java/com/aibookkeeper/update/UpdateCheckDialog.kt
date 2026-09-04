package com.aibookkeeper.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aibookkeeper.BuildConfig
import com.aibookkeeper.core.common.changelog.CHANGELOG
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.core.data.update.ReleaseInfo
import com.aibookkeeper.core.data.update.UpdateChecker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateCheckEntryPoint {
    fun secureConfigStore(): SecureConfigStore
}

@Composable
fun UpdateCheckEffect() {
    val context = LocalContext.current
    val configStore = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            UpdateCheckEntryPoint::class.java
        ).secureConfigStore()
    }
    var releaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = downloadedApk
        if (apk != null && canInstallPackages(context)) {
            launchPackageInstaller(context, apk)
            showDialog = false
        } else {
            downloadError = "需要允许安装未知应用，授权后请再次点击安装"
        }
    }

    LaunchedEffect(configStore) {
        val info = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@LaunchedEffect
        if (info.version == configStore.getIgnoredUpdateVersion()) return@LaunchedEffect

        releaseInfo = info
        showDialog = true
    }

    if (showDialog && releaseInfo != null) {
        val info = releaseInfo!!
        val changelogEntry = CHANGELOG.firstOrNull { it.version == info.version }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("发现新版本 v${info.version}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (changelogEntry != null) {
                        Text(
                            "更新内容：",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        changelogEntry.highlights.forEach { highlight ->
                            Text(
                                text = highlight,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                            )
                        }
                    } else if (info.body.isNotBlank()) {
                        Text(info.body.take(500))
                    } else {
                        Text("新版本已发布，建议更新以获得最新功能和修复。")
                    }
                    if (downloadError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(downloadError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloading,
                    onClick = {
                        val existingApk = downloadedApk
                        if (existingApk != null && existingApk.exists()) {
                            requestInstall(context, existingApk) { installPermissionLauncher.launch(it) }
                        } else {
                            downloadError = ""
                            isDownloading = true
                            scope.launch {
                                try {
                                    val apk = downloadApk(context, info)
                                    downloadedApk = apk
                                    requestInstall(context, apk) { installPermissionLauncher.launch(it) }
                                } catch (exception: Exception) {
                                    downloadError = exception.message ?: "更新包下载失败，请稍后重试"
                                } finally {
                                    isDownloading = false
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isDownloading) "下载中…" else if (downloadedApk != null) "安装更新" else "下载并安装")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        configStore.setIgnoredUpdateVersion(info.version)
                        showDialog = false
                    }
                ) {
                    Text("忽略此版本")
                }
            }
        )
    }
}

private suspend fun downloadApk(context: Context, info: ReleaseInfo): File =
    withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "ai-bookkeeper-${info.version}.apk")
        val temporary = File(context.cacheDir, "${target.name}.download")
        val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("更新包下载失败（HTTP ${connection.responseCode}）")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 0) { "下载的更新包为空" }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("无法替换旧的更新包")
            }
            check(temporary.renameTo(target)) { "无法保存更新包" }
            target
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

private fun canInstallPackages(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

private fun requestInstall(
    context: Context,
    apk: File,
    requestPermission: (Intent) -> Unit
) {
    if (canInstallPackages(context)) {
        launchPackageInstaller(context, apk)
    } else {
        requestPermission(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

private fun launchPackageInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}
