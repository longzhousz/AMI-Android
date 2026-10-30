package com.longzhousz.ami.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.core.TranslationModels
import com.longzhousz.ami.data.TranslationModelState

@Composable
internal fun TranslationModelDialog(
    state: TranslationModelState,
    hasKey: Boolean,
    refresh: () -> Unit,
    automaticUpdates: (Boolean) -> Unit,
    select: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var selection by remember { mutableStateOf(state.selection) }
    var updates by remember { mutableStateOf(state.automaticUpdates) }
    var manual by remember { mutableStateOf(!state.selection.startsWith("auto:")) }
    var custom by remember { mutableStateOf(state.selection.takeUnless { it.startsWith("auto:") }.orEmpty()) }
    val selected = if (selection == "custom") custom.trim() else selection
    val resolved = runCatching { TranslationModels.resolve(selected, state.models) }.getOrNull()
    GlassAlertDialog(dismiss, title = { Text("翻译模型") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("translation-model-dialog")) {
            Text("跟随官方最新模型，无需更新 AMI。已有译文会保留，重新翻译时使用当前模型。", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("自动更新预设", style = MaterialTheme.typography.titleSmall)
                    Text("保存密钥后，前台每天检查一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(updates, { updates = it }, Modifier.testTag("automatic-model-updates"))
            }
            TextButton(refresh, enabled = hasKey && !state.refreshing, contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.refreshing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (state.refreshing) "正在获取官方列表…" else "立即更新预设")
            }
            if (!hasKey) Text("保存 DeepSeek API Key 后可获取官方列表。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            listOf(TranslationModels.AUTO_FLASH to "自动跟随最新 Flash", TranslationModels.AUTO_PRO to "自动跟随最新 Pro").forEach { (id, label) ->
                val target = runCatching { TranslationModels.resolve(id, state.models) }.getOrNull()
                ModelChoice(label, target?.name ?: "官方列表暂未提供此系列", selection == id, target != null) { selection = id }
            }
            TextButton({ manual = !manual }) { Text(if (manual) "收起手动选择" else "手动选择") }
            if (manual) {
            state.models.forEach { model ->
                ModelChoice(model.name, model.id, selection == model.id) { selection = model.id; custom = model.id }
            }
            ModelChoice("手动输入模型 ID", "保留你的选择，不随预设刷新而切换", selection == "custom" ||
                (!selection.startsWith("auto:") && state.models.none { it.id == selection })) { selection = "custom" }
            if (selection == "custom" || (!selection.startsWith("auto:") && state.models.none { it.id == selection })) {
                OutlinedTextField(custom, { custom = it; selection = "custom" }, Modifier.fillMaxWidth().testTag("custom-model-id"),
                    label = { Text("模型 ID") }, singleLine = true,
                    isError = custom.isNotBlank() && !TranslationModels.validId(custom.trim()),
                    supportingText = { Text("使用 DeepSeek 官方 API 支持的模型 ID") })
            }
            }
        }
    }, confirmButton = { TextButton({ automaticUpdates(updates); select(selected) }, enabled = resolved != null) { Text("保存") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable
private fun ModelChoice(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != title) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
