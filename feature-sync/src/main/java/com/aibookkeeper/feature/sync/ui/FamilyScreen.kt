package com.aibookkeeper.feature.sync.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibookkeeper.feature.sync.auth.AuthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FamilyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findHostActivity()
    var showPersonalConfirmation by remember { mutableStateOf(false) }
    var showCreateLedger by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账本与家庭管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (uiState.authState is AuthState.SignedIn) {
                        IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                            Icon(Icons.Default.Refresh, "刷新账本和成员名称")
                        }
                        IconButton(onClick = { showCreateLedger = true }) {
                            Icon(Icons.Default.Add, "新建账本")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        when (uiState.authState) {
            AuthState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            AuthState.SignedOut, is AuthState.Error -> SignInRequired(
                onSignIn = { activity?.let(viewModel::signIn) },
                enabled = activity != null,
                modifier = Modifier.padding(innerPadding)
            )
            is AuthState.SignedIn -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.message != null) {
                    item {
                        Text(
                            text = uiState.message.orEmpty(),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = viewModel::clearMessage)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                if (uiState.invitations.isNotEmpty()) {
                    item { SectionTitle("待接受邀请") }
                    items(uiState.invitations, key = { it.id }) { invitation ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(invitation.ledgerName, fontWeight = FontWeight.Bold)
                                Text(
                                    "${invitation.inviterLabel} · " +
                                        roleLabel(invitation.role),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = {
                                    viewModel.acceptInvitation(invitation.id)
                                }
                            ) {
                                Text("接受")
                            }
                        }
                    }
                    item { HorizontalDivider() }
                }

                item { SectionTitle("账本") }
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp
                        )
                    ) {
                        items(uiState.ledgers, key = { it.id }) { ledger ->
                            FilterChip(
                                selected = ledger.id == uiState.selectedLedgerId,
                                onClick = { viewModel.selectLedger(ledger.id) },
                                label = {
                                    Column {
                                        Text("${ledger.name} · ${roleLabel(ledger.role)}")
                                        if (ledger.mode == "FAMILY") {
                                            Text(
                                                "所有者：${ledger.ownerLabel}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                uiState.selectedLedger?.takeIf { it.mode == "FAMILY" }?.let { ledger ->
                    item {
                        Text(
                            text = "账本所有者：${ledger.ownerLabel}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (uiState.isOwner) {
                    item {
                        val ledger = uiState.selectedLedger
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (ledger?.mode == "FAMILY") {
                                        "当前为家庭账本"
                                    } else {
                                        "当前为个人账本"
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (ledger?.mode == "FAMILY") {
                                        "成员可按权限查看或编辑"
                                    } else {
                                        "只有你本人可以访问"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    if (ledger?.mode == "FAMILY") {
                                        showPersonalConfirmation = true
                                    } else {
                                        viewModel.convertLedger("FAMILY")
                                    }
                                }
                            ) {
                                Text(
                                    if (ledger?.mode == "FAMILY") {
                                        "转为个人"
                                    } else {
                                        "转为家庭"
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.selectedLedger?.mode == "FAMILY") {
                        item {
                            InviteMemberForm(onInvite = viewModel::invite)
                        }
                        item { SectionTitle("成员权限") }
                        if (
                            uiState.members.isEmpty() &&
                            uiState.pendingInvitations.isEmpty()
                        ) {
                            item {
                                Text(
                                    "尚未添加成员",
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                        items(uiState.members, key = { it.id }) { member ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(member.displayLabel)
                                    if (member.displayLabel != member.email) {
                                        Text(member.email, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        roleLabel(member.role),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.updateMember(
                                            member.id,
                                            member.role != "EDITOR"
                                        )
                                    }
                                ) {
                                    Text(
                                        if (member.role == "EDITOR") {
                                            "改为查看"
                                        } else {
                                            "改为编辑"
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.removeMember(member.id)
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, "移除成员")
                                }
                            }
                        }
                        items(
                            uiState.pendingInvitations,
                            key = { it.id }
                        ) { invitation ->
                            Text(
                                "等待 ${invitation.email} 接受 · " +
                                    roleLabel(invitation.role),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                if (uiState.isLoading) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                item {
                    Text(
                        "账单展示与记账请返回首页，通过左上角切换账本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showPersonalConfirmation) {
        AlertDialog(
            onDismissRequest = { showPersonalConfirmation = false },
            title = { Text("转换为个人账本？") },
            text = {
                Text("账单会保留，但所有家庭成员权限和未接受邀请都会被撤销。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPersonalConfirmation = false
                        viewModel.convertLedger("PERSONAL")
                    }
                ) {
                    Text("确认转换")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPersonalConfirmation = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showCreateLedger) {
        CreateLedgerDialog(
            onDismiss = { showCreateLedger = false },
            onCreate = { name, mode ->
                showCreateLedger = false
                viewModel.createLedger(name, mode)
            }
        )
    }
}

@Composable
private fun CreateLedgerDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("PERSONAL") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建账本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账本名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "PERSONAL",
                        onClick = { mode = "PERSONAL" },
                        label = { Text("个人") }
                    )
                    FilterChip(
                        selected = mode == "FAMILY",
                        onClick = { mode = "FAMILY" },
                        label = { Text("家庭") }
                    )
                }
                Text(
                    if (mode == "FAMILY") {
                        "创建后可邀请成员共同查看或记账。"
                    } else {
                        "仅你本人可访问，之后可转为家庭账本。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), mode) },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun SignInRequired(
    onSignIn: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("登录后即可查看个人或家庭账本")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSignIn, enabled = enabled) {
            Text("使用邮箱验证码登录")
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@Composable
private fun InviteMemberForm(
    onInvite: (String, Boolean) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("成员邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("允许编辑", modifier = Modifier.weight(1f))
            Switch(checked = canEdit, onCheckedChange = { canEdit = it })
        }
        Button(
            onClick = {
                onInvite(email, canEdit)
                email = ""
            },
            enabled = email.contains("@"),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("发送邀请")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

private fun roleLabel(role: String): String = when (role) {
    "OWNER" -> "所有者"
    "EDITOR" -> "可编辑"
    else -> "仅查看"
}
