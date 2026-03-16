package com.aibookkeeper.update

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aibookkeeper.BuildConfig
import com.aibookkeeper.core.common.changelog.CHANGELOG
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.core.data.update.ReleaseInfo
import com.aibookkeeper.core.data.update.UpdateChecker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                        showDialog = false
                    }
                ) {
                    Text("去更新")
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
