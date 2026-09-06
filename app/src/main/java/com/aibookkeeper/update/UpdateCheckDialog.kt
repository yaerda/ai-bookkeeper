package com.aibookkeeper.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aibookkeeper.BuildConfig
import com.aibookkeeper.core.common.changelog.CHANGELOG
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.core.data.update.ApkDownloadEvent
import com.aibookkeeper.core.data.update.ApkDownloadPhase
import com.aibookkeeper.core.data.update.ApkDownloadProgress
import com.aibookkeeper.core.data.update.ApkDownloader
import com.aibookkeeper.core.data.update.ReleaseInfo
import com.aibookkeeper.core.data.update.UpdateChecker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    var progress by remember { mutableStateOf<ApkDownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val downloader = remember { ApkDownloader() }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = downloadedApk
        if (apk == null || !apk.exists()) {
            downloadedApk = null
            progress = null
            downloadError = "更新包已失效，请重新下载"
        } else if (canInstallPackages(context)) {
            try {
                launchPackageInstaller(context, apk)
                showDialog = false
            } catch (_: ActivityNotFoundException) {
                downloadError = "找不到系统安装程序"
            } catch (_: SecurityException) {
                downloadError = "系统未允许安装，请检查安装权限"
            }
        } else {
            downloadError = "需要允许安装未知应用，授权后请再次点击安装"
        }
    }

    LaunchedEffect(configStore) {
        val info = try {
            UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@LaunchedEffect
        } catch (error: IOException) {
            Log.w("UpdateCheck", "无法检查新版本", error)
            return@LaunchedEffect
        }
        if (info.version == configStore.getIgnoredUpdateVersion()) return@LaunchedEffect

        releaseInfo = info
        showDialog = true
    }

    if (showDialog && releaseInfo != null) {
        val info = releaseInfo!!
        val changelogEntry = CHANGELOG.firstOrNull { it.version == info.version }

        AlertDialog(
            onDismissRequest = { if (!isDownloading) showDialog = false },
            title = { Text("发现新版本 v${info.version}") },
            text = {
                Column {
                    Column(modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        if (changelogEntry != null) {
                            Text(
                                "更新内容：",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            changelogEntry.highlights.forEach { highlight ->
                                Text(highlight, style = MaterialTheme.typography.bodyMedium)
                            }
                        } else if (info.body.isNotBlank()) {
                            Text(info.body.take(500))
                        } else {
                            Text("新版本已发布，建议更新以获得最新功能和修复。")
                        }
                    }
                    progress?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        UpdateDownloadProgress(it)
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
                    onClick = download@{
                        if (isDownloading) return@download
                        val existingApk = downloadedApk
                        if (existingApk != null && existingApk.exists()) {
                            try {
                                requestInstall(context, existingApk) { installPermissionLauncher.launch(it) }
                            } catch (_: ActivityNotFoundException) {
                                downloadError = "找不到系统安装程序"
                            } catch (_: SecurityException) {
                                downloadError = "系统未允许安装，请检查安装权限"
                            }
                        } else {
                            downloadError = ""
                            downloadedApk = null
                            isDownloading = true
                            progress = ApkDownloadProgress(
                                0, info.sizeBytes, info.downloadSourceName, ApkDownloadPhase.CONNECTING
                            )
                            downloadJob = scope.launch {
                                try {
                                    downloader.download(info, context.cacheDir).collect { event ->
                                        when (event) {
                                            is ApkDownloadEvent.Progress -> progress = event.value
                                            is ApkDownloadEvent.Complete -> {
                                                progress = event.progress
                                                downloadedApk = event.file
                                                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                                    requestInstall(context, event.file) { installPermissionLauncher.launch(it) }
                                                }
                                            }
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    downloadError = "下载已取消，可重新下载"
                                    progress = null
                                    throw cancelled
                                } catch (exception: IOException) {
                                    downloadError = exception.message ?: "更新包下载失败，请稍后重试"
                                    progress = null
                                } catch (_: ActivityNotFoundException) {
                                    downloadError = "找不到系统安装程序"
                                } catch (_: SecurityException) {
                                    downloadError = "无法访问更新包或安装程序，请检查权限"
                                } finally {
                                    isDownloading = false
                                    downloadJob = null
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
                        if (isDownloading) {
                            downloadJob?.cancel()
                        } else {
                            configStore.setIgnoredUpdateVersion(info.version)
                            showDialog = false
                        }
                    }
                ) {
                    Text(if (isDownloading) "取消下载" else "忽略此版本")
                }
            }
        )
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
