package com.aibookkeeper.feature.input.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibookkeeper.core.common.util.CategoryIconMapper

@Composable
fun AddCategoryDialog(
    name: String,
    presetIcon: String,
    customEmoji: String,
    onNameChange: (String) -> Unit,
    onPresetIconSelected: (String) -> Unit,
    onCustomEmojiChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加新分类") },
        text = {
            Column {
                CategoryNameAndEmojiFields(
                    name = name,
                    onNameChange = onNameChange,
                    customEmoji = customEmoji,
                    onCustomEmojiChange = onCustomEmojiChange
                )
                Spacer(modifier = Modifier.height(12.dp))
                CategoryIconEditor(
                    presetIcon = presetIcon,
                    customEmoji = customEmoji,
                    onPresetIconSelected = onPresetIconSelected
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryIconEditor(
    presetIcon: String,
    customEmoji: String,
    onPresetIconSelected: (String) -> Unit
) {
    val selectedIcon = resolveCategoryIcon(presetIcon, customEmoji)

    Text("选择图标", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = CategoryIconMapper.getEmoji(selectedIcon), fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "也可以在上面的 Emoji 框直接输入自定义图标",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CategoryIconMapper.allIcons.forEach { (iconKey, emoji) ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (customEmoji.isBlank() && presetIcon == iconKey)
                            MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable { onPresetIconSelected(iconKey) },
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 20.sp)
            }
        }
    }
}

@Composable
internal fun CategoryNameAndEmojiFields(
    name: String,
    onNameChange: (String) -> Unit,
    customEmoji: String,
    onCustomEmojiChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = customEmoji,
            onValueChange = onCustomEmojiChange,
            label = { Text("Emoji") },
            placeholder = { Text("🥬") },
            singleLine = true,
            modifier = Modifier.width(92.dp)
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("分类名称") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

fun resolveCategoryIcon(presetIcon: String, customEmoji: String): String =
    customEmoji.trim().ifBlank { presetIcon }
