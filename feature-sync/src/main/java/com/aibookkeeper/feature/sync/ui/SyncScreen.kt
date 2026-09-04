package com.aibookkeeper.feature.sync.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.queue.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle(
        initialValue = SyncState.IDLE
    )
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val message by viewModel.message.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val auth = authState) {
                AuthState.Loading -> CircularProgressIndicator()
                AuthState.SignedOut -> {
                    Icon(Icons.Default.CloudOff, null)
                    Spacer(Modifier.height(16.dp))
                    Text("登录后可将账单安全同步到你的 Azure 账户")
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { activity?.let(viewModel::signIn) },
                        enabled = activity != null
                    ) {
                        Text("使用邮箱验证码登录")
                    }
                }
                is AuthState.SignedIn -> {
                    Icon(Icons.Default.CloudDone, null)
                    Spacer(Modifier.height(12.dp))
                    Text(auth.email, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when (syncState) {
                            SyncState.IDLE -> "待同步 $pendingCount 笔"
                            SyncState.SYNCING -> "正在同步…"
                            SyncState.SUCCESS -> "同步完成"
                            SyncState.ERROR -> "同步失败，请重试"
                        },
                        color = if (syncState == SyncState.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (message != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message.orEmpty(),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = syncState != SyncState.SYNCING,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("立即同步")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::signOut,
                        enabled = syncState != SyncState.SYNCING,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("退出登录（保留本地数据）")
                    }
                }
                is AuthState.Error -> {
                    Icon(Icons.Default.CloudOff, null)
                    Spacer(Modifier.height(12.dp))
                    Text(auth.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { activity?.let(viewModel::signIn) },
                        enabled = activity != null
                    ) {
                        Text("重试登录")
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
