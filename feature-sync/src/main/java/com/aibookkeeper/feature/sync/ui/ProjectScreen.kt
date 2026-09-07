package com.aibookkeeper.feature.sync.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.isActiveAt
import com.aibookkeeper.core.data.model.ProjectScope
import com.aibookkeeper.core.data.model.ProjectStats
import com.aibookkeeper.core.data.repository.LOCAL_LEDGER_ID

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectViewModel = hiltViewModel()
) {
    val ledgerState by viewModel.ledgerState.collectAsStateWithLifecycle()
    val projectState by viewModel.projectState.collectAsStateWithLifecycle()
    val scope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val stats by viewModel.selectedStats.collectAsStateWithLifecycle()
    val selectedProjectId by viewModel.selectedProjectId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var showCreate by remember(ledgerState.selection, projectState.accountId) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("项目管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新项目")
                    }
                    IconButton(
                        onClick = { showCreate = true },
                        enabled = ledgerState.ledgers.any { it.canEdit && it.id != LOCAL_LEDGER_ID }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建项目")
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
                ) {
                    items(ledgerState.ledgers, key = { it.id }) { ledger ->
                        FilterChip(
                            selected = ledger.id == ledgerState.selectedLedgerId,
                            onClick = { viewModel.selectLedger(ledger.id) },
                            label = { Text(ledger.name) }
                        )
                    }
                }
            }

            val statusMessage = message ?: projectState.errorMessage
            if (!statusMessage.isNullOrBlank()) {
                item {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = if (message != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Text(
                    text = "当前账本项目",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (projectState.projects.isEmpty() && !projectState.isLoading) {
                item {
                    Text(
                        text = if (ledgerState.isSignedIn) "当前账本还没有项目" else "登录后可管理项目",
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                items(projectState.projects, key = { it.projectId }) { project ->
                    ProjectListItem(
                        binding = project,
                        selected = selectedProjectId == project.projectId,
                        onClick = { viewModel.selectProject(project.projectId) }
                    )
                }
            }

            if (projectState.isLoading || isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            scope?.let { selectedScope ->
                item {
                    ProjectScopeCard(
                        scope = selectedScope,
                        availableWritableLedgers = ledgerState.ledgers
                            .filter { it.id != LOCAL_LEDGER_ID && it.canEdit }
                            .map { it.id to it.name },
                        onSaveBinding = { bindingLedgerId, version, enabled, startDate, endDate ->
                            viewModel.saveLedgerBinding(
                                projectId = selectedScope.projectId,
                                ledgerId = bindingLedgerId,
                                version = version,
                                enabled = enabled,
                                startDate = startDate,
                                endDate = endDate
                            )
                        }
                    )
                }
            }

            stats?.let { projectStats ->
                item {
                    ProjectStatsCard(projectStats)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showCreate) {
        val writableLedgers = ledgerState.ledgers
            .filter { it.canEdit && it.id != LOCAL_LEDGER_ID }
            .map { it.id to it.name }
        CreateProjectDialog(
            ledgers = writableLedgers,
            onDismiss = { showCreate = false },
            onCreate = { name, ledgerIds, enabled, startDate, endDate ->
                showCreate = false
                viewModel.createProject(name, ledgerIds, enabled, startDate, endDate)
            }
        )
    }
}

@Composable
private fun ProjectListItem(
    binding: ProjectBinding,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    "${binding.name} · ${if (binding.isActiveAt(java.time.Instant.now())) "生效中" else "未默认"}",
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        )
    }
}

@Composable
private fun CreateProjectDialog(
    ledgers: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>?, Boolean, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var selectedLedgers by remember { mutableStateOf(ledgers.map { it.first }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("默认启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("开始日期（yyyy-MM-dd，可空）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("结束日期（yyyy-MM-dd，可空）") },
                    singleLine = true
                )
                Text("适用账本", fontWeight = FontWeight.Bold)
                ledgers.forEach { (ledgerId, ledgerName) ->
                    FilterChip(
                        selected = ledgerId in selectedLedgers,
                        onClick = {
                            selectedLedgers = if (ledgerId in selectedLedgers) {
                                selectedLedgers - ledgerId
                            } else {
                                selectedLedgers + ledgerId
                            }
                        },
                        label = { Text(ledgerName) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.trim(),
                        selectedLedgers.toList(),
                        enabled,
                        startDate.ifBlank { null },
                        endDate.ifBlank { null }
                    )
                },
                enabled = name.isNotBlank() && selectedLedgers.size in 1..100
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
private fun ProjectScopeCard(
    scope: ProjectScope,
    availableWritableLedgers: List<Pair<String, String>>,
    onSaveBinding: (String, Long, Boolean, String?, String?) -> Unit
) {
    val existingByLedger = scope.ledgers.associateBy { it.ledgerId }
    CardSection(title = "项目范围：${scope.name}") {
        val allLedgers = buildList {
            addAll(availableWritableLedgers)
            addAll(scope.ledgers.map { it.ledgerId to it.ledgerId })
        }.distinctBy { it.first }
        for ((ledgerId, ledgerName) in allLedgers) {
            val binding = existingByLedger[ledgerId]
            var enabled by remember(scope.projectId, ledgerId, binding?.enabled) {
                mutableStateOf(binding?.enabled ?: false)
            }
            var startDate by remember(scope.projectId, ledgerId, binding?.startDate) {
                mutableStateOf(binding?.startDate.orEmpty())
            }
            var endDate by remember(scope.projectId, ledgerId, binding?.endDate) {
                mutableStateOf(binding?.endDate.orEmpty())
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(ledgerName, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("默认启用", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        enabled = binding?.canEdit != false
                    )
                }
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("开始日期") },
                    singleLine = true,
                    enabled = binding?.canEdit != false,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("结束日期") },
                    singleLine = true,
                    enabled = binding?.canEdit != false,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onSaveBinding(
                            ledgerId,
                            binding?.version ?: 0L,
                            enabled,
                            startDate.ifBlank { null },
                            endDate.ifBlank { null }
                        )
                    },
                    enabled = binding?.canEdit != false
                ) {
                    Text("保存账本配置")
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ProjectStatsCard(stats: ProjectStats) {
    CardSection(title = "项目统计：${stats.name}") {
        Text("收支笔数：${stats.transactionCount}")
        Text("收入：${stats.income}")
        Text("支出：${stats.expense}")
        Text("结余：${stats.balance}")
        Spacer(modifier = Modifier.height(8.dp))
        stats.ledgers.forEach { ledger ->
            Text("${ledger.ledgerName} · ${ledger.transactionCount}笔 · 收入 ${ledger.income} · 支出 ${ledger.expense}")
        }
    }
}

@Composable
private fun CardSection(
    title: String,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
