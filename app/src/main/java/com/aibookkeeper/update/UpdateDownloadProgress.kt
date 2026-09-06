package com.aibookkeeper.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aibookkeeper.core.data.update.ApkDownloadPhase
import com.aibookkeeper.core.data.update.ApkDownloadProgress

@Composable
internal fun UpdateDownloadProgress(progress: ApkDownloadProgress) {
    val context = LocalContext.current
    val downloaded = Formatter.formatShortFileSize(context, progress.downloadedBytes)
    val fraction = progress.fraction
    val totalBytes = progress.totalBytes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when (progress.phase) {
                ApkDownloadPhase.CONNECTING -> "正在连接${progress.sourceName}…"
                ApkDownloadPhase.DOWNLOADING -> "下载来源：${progress.sourceName}"
                ApkDownloadPhase.VERIFYING -> "正在校验更新包…"
                ApkDownloadPhase.COMPLETE -> "下载完成"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (fraction != null && totalBytes != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().testTag("update-download-progress")
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${progress.percent}%", modifier = Modifier.testTag("update-download-percent"))
                Text(
                    "$downloaded / ${Formatter.formatShortFileSize(context, totalBytes)}",
                    modifier = Modifier.testTag("update-download-size")
                )
            }
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("update-download-progress")
            )
            Text("已下载 $downloaded（总大小未知）", modifier = Modifier.testTag("update-download-size"))
        }
        progress.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
