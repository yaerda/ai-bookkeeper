package com.aibookkeeper.feature.input.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibookkeeper.core.data.model.ProjectDefaultsAvailability
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.isActiveAt
import java.time.Instant

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ProjectSelectionSection(
    state: ProjectLedgerState,
    selectedProjectIds: List<String>?,
    onSelectedProjectIdsChange: (List<String>?) -> Unit,
    unspecifiedLabel: String,
    modifier: Modifier = Modifier,
    unspecifiedProjectIds: List<String>? = state.defaultProjectIds,
    enabled: Boolean = true
) {
    val effectiveIds = selectedProjectIds ?: unspecifiedProjectIds.orEmpty()
    Column(modifier = modifier.fillMaxWidth()) {
        Text("项目", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedProjectIds == null,
                enabled = enabled,
                onClick = { onSelectedProjectIdsChange(null) },
                label = { Text(unspecifiedLabel) }
            )
            FilterChip(
                selected = selectedProjectIds != null && selectedProjectIds.isEmpty(),
                enabled = enabled,
                onClick = { onSelectedProjectIdsChange(emptyList()) },
                label = { Text("不关联项目") }
            )
            state.projects.forEach { project ->
                val selected = project.projectId in effectiveIds
                val label = buildString {
                    append(project.name)
                    if (!project.isActiveAt(Instant.now())) append(if (project.enabled) "（非生效期）" else "（停用）")
                }
                if (selected) {
                    AssistChip(
                        enabled = enabled,
                        onClick = {
                            val next = effectiveIds - project.projectId
                            onSelectedProjectIdsChange(next)
                        },
                        label = { Text(label) }
                    )
                } else {
                    FilterChip(
                        selected = false,
                        enabled = enabled,
                        onClick = {
                            val next = (effectiveIds + project.projectId).distinct()
                            onSelectedProjectIdsChange(next)
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
        val helper = when {
            state.errorMessage != null && state.projects.isNotEmpty() ->
                "项目默认值正在使用缓存：${state.errorMessage}"

            state.errorMessage != null -> state.errorMessage
            state.availability == ProjectDefaultsAvailability.UNAVAILABLE ->
                "项目默认值当前不可用，保存时将由云端决定"

            state.availability == ProjectDefaultsAvailability.CACHED ->
                "项目默认值来自缓存，恢复联网后会自动刷新"

            else -> null
        }
        if (!helper.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
