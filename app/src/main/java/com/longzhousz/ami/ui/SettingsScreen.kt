package com.longzhousz.ami.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.data.LibraryRepository
import com.longzhousz.ami.data.TranslationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SettingsScreen(model: AmiViewModel, onImport: () -> Unit, onNotifications: () -> Unit, onBridgeFolder: () -> Unit, onLyricsFolder: () -> Unit) {
    val repo = model.repository
    val songs by repo.songs.collectAsStateWithLifecycle()
    val importState by repo.importState.collectAsStateWithLifecycle()
    val theme by model.theme.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var roots by remember { mutableStateOf(repo.roots()) }
    var lyricRoots by remember { mutableStateOf(repo.lyricRoots()) }
    var lyricCount by remember { mutableIntStateOf(0) }
    var cache by remember { mutableStateOf(LibraryRepository.CacheInfo(0, 0, 0)) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    val modelState by model.translationModels.state.collectAsStateWithLifecycle()
    val style by model.translationStyle.collectAsStateWithLifecycle()
    val savedKey by model.hasKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(importState.running) {
        cache = withContext(Dispatchers.IO) { repo.cacheInfo() }
        lyricCount = withContext(Dispatchers.IO) { repo.db.lyricsCount() }
        roots = repo.roots(); lyricRoots = repo.lyricRoots()
    }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 30.dp)) {
        item {
            SectionLabel("我的曲库")
            SettingsGroup {
                SettingRow(Icons.Rounded.CreateNewFolder, "导入音乐", "音频、LRC、CUE 与经典 NCM", onImport)
                SettingRow(Icons.Rounded.Sync, "重新扫描", "${roots.size} 个已授权文件夹 · ${songs.size} 首歌曲", { if (roots.isNotEmpty()) repo.scan() else onImport() }, trailing = {
                    if (importState.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ChevronRight, null)
                })
                SettingRow(Icons.Rounded.FolderOpen, "管理音乐文件夹", "只读取你选择的文件夹", { roots = repo.roots(); dialog = "roots" })
                if (importState.message.isNotEmpty() && !importState.running) SettingRow(Icons.Rounded.FactCheck, "最近导入结果", importState.message, { dialog = "import" })
            }
            SectionLabel("歌词与翻译")
            SettingsGroup {
                SettingRow(Icons.Rounded.Lyrics, "网易云歌词", "$lyricCount 份歌词 · 按歌曲 ID 自动关联", { lyricRoots = repo.lyricRoots(); dialog = "neteaseLyrics" })
                SettingRow(Icons.Rounded.Key, "DeepSeek API Key", if (savedKey) "已加密保存 · 点击更换或删除" else "用于翻译与获取官方模型列表", { dialog = "key" })
                SettingRow(Icons.Rounded.AutoAwesome, "翻译模型", modelState.summary, { model.refreshModels(); dialog = "model" })
                SettingRow(Icons.Rounded.EditNote, "翻译风格", "按顺序遵循你的表达偏好", { dialog = "style" })
                SettingRow(Icons.Rounded.NetworkCheck, "测试连接", "发送一条简短测试消息", { model.testConnection() })
            }
            SectionLabel("聆听与外观")
            SettingsGroup {
                SettingRow(Icons.Rounded.Palette, "外观", when (theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, { dialog = "theme" })
                SettingRow(Icons.Rounded.NotificationsNone, "导入进度通知", "允许后台导入时显示进度", onNotifications)
            }
            SectionLabel("存储与隐私")
            SettingsGroup {
                SettingRow(Icons.Rounded.Storage, "缓存管理", "${megabytes(cache.bytes)} · ${cache.files} 个文件", { scope.launch { cache = withContext(Dispatchers.IO) { repo.cacheInfo() }; dialog = "cache" } })
                SettingRow(Icons.Rounded.SmartToy, "Agent 与自动化", "默认关闭 · 仅处理授权文件夹中的请求", { dialog = "agent" })
                SettingRow(Icons.Rounded.History, "操作记录", "最近 50 条 · 仅保存在本机", { scope.launch { logs = withContext(Dispatchers.IO) { repo.db.actionLog() }; dialog = "logs" } })
                SettingRow(Icons.Rounded.Security, "隐私说明", "音频与封面始终留在本机", { dialog = "privacy" })
            }
            Text("AMI ${com.longzhousz.ami.BuildConfig.VERSION_NAME}", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (dialog) {
        "neteaseLyrics" -> AlertDialog({ dialog = null }, title = { Text("网易云歌词") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("将 LrcDownload 文件夹整体导入，数字文件名无需修改。AMI 会读取 NCM 内的歌曲 ID，自动找到对应歌词。", style = MaterialTheme.typography.bodyMedium)
                Text("网易云原目录", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
                Text("Android/data/com.netease.cloudmusic/files/LrcDownload", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                Text("Android 11 及以上限制读取其他应用的 Android/data。请先用有权限的文件管理器或电脑，将 LrcDownload 复制到 Music/AMI-Lyrics 等普通文件夹，再在这里选择。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("支持没有扩展名的数字文件、LRC 和网易云 JSON 缓存。原文与已有译文会保存在本机，之后可离线使用。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                Text("没有歌曲 ID 的音频，可在播放页右上角的歌词选项中手动选择文件。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Text("已保存 $lyricCount 份 · 已关联 ${songs.count { it.lyricsSource.startsWith("网易云 ·") }} 首", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
                lyricRoots.forEach { root ->
                    Text(Uri.decode(Uri.parse(root).lastPathSegment.orEmpty()), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton({ repo.forgetLyricRoot(root); lyricRoots = repo.lyricRoots() }) { Text("停止扫描此歌词文件夹") }
                }
                if (lyricRoots.isNotEmpty()) TextButton({ repo.scan(); dialog = null }, enabled = !importState.running) { Text("重新扫描并匹配歌词") }
            }
        }, confirmButton = { TextButton({ dialog = null; onLyricsFolder() }, enabled = !importState.running) { Text("选择歌词文件夹") } },
            dismissButton = { TextButton({ dialog = null }) { Text("完成") } })
        "key" -> {
            var key by remember { mutableStateOf("") }
            AlertDialog({ dialog = null }, title = { Text("DeepSeek API Key") }, text = {
                Column {
                    Text("密钥使用设备密钥加密。AMI 不会显示已保存的密钥。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text("粘贴新的 API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
                    if (savedKey) TextButton({ model.saveKey(""); dialog = null }) { Text("删除已保存的密钥") }
                }
            }, confirmButton = { TextButton({ model.saveKey(key); key = ""; dialog = null }, enabled = key.isNotBlank()) { Text("加密保存") } }, dismissButton = { TextButton({ key = ""; dialog = null }) { Text("取消") } })
        }
        "model" -> TranslationModelDialog(modelState, savedKey, { model.refreshModels(force = true) },
            model::setAutomaticModelUpdates, { model.selectTranslationModel(it); dialog = null }, { dialog = null })
        "theme" -> ChoiceDialog("外观", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), theme, { model.setTheme(it); dialog = null }, { dialog = null })
        "style" -> {
            var draft by remember { mutableStateOf(style) }
            AlertDialog({ dialog = null }, title = { Text("翻译风格") }, text = {
                Column { Text("每行一个要求，越靠前优先级越高。修改风格后会生成新的翻译。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft, { if (it.length <= 3000) draft = it }, Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 6, maxLines = 10)
                    TextButton({ draft = TranslationClient.DEFAULT_STYLE }) { Text("恢复默认风格") }
                }
            }, confirmButton = { TextButton({ model.setTranslationStyle(draft); dialog = null }, enabled = draft.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ dialog = null }) { Text("取消") } })
        }
        "roots" -> AlertDialog({ dialog = null }, title = { Text("音乐文件夹") }, text = {
            LazyColumn { if (roots.isEmpty()) item { Text("还没有添加文件夹") }
                roots.forEach { root -> item { Column(Modifier.padding(vertical = 8.dp)) {
                    Text(Uri.decode(Uri.parse(root).lastPathSegment.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ repo.forgetRoot(root); roots = repo.roots() }) { Text("停止扫描此文件夹") }
                } } }
                item { Text("停止扫描后，已导入的音乐仍会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }, confirmButton = { TextButton({ dialog = null; onImport() }) { Text("添加文件夹") } }, dismissButton = { TextButton({ dialog = null }) { Text("完成") } })
        "import" -> InfoDialog("最近导入结果", importState.message + if (importState.errors.isEmpty()) "\n\n没有发现问题。" else "\n\n" + importState.errors.joinToString("\n"), { dialog = null })
        "cache" -> AlertDialog({ dialog = null }, title = { Text("缓存管理") }, text = { Text("音乐转换与封面缓存：${megabytes(cache.bytes)}\n可安全清理：${megabytes(cache.reclaimable)}\n\n只清理未被曲库引用的应用缓存。正在使用的音频缓存和原始音乐都会保留。") },
            confirmButton = { TextButton({ scope.launch { runCatching { withContext(Dispatchers.IO) { repo.cleanup() } }.onSuccess { model.message.value = "已清理 ${megabytes(it)}" }.onFailure { model.message.value = it.message }; dialog = null } }, enabled = !importState.running && cache.reclaimable > 0) { Text("清理未引用缓存") } }, dismissButton = { TextButton({ dialog = null }) { Text("关闭") } })
        "logs" -> InfoDialog("操作记录", logs.joinToString("\n\n").ifBlank { "还没有操作记录" }, { dialog = null })
        "agent" -> AgentDialog(model, onBridgeFolder, { dialog = null })
        "privacy" -> InfoDialog("隐私说明", "AMI 只读取你授权的音乐文件夹，不改写原始音频或标签。\n\n音频、封面、路径和曲库不会上传。保存 API Key 后，应用会在前台每天检查官方模型列表；可在翻译模型中关闭自动更新。获取列表只发送鉴权信息，不发送歌词，也不发起翻译。\n\n你主动翻译时只发送歌词文本、目标语言、模型和风格要求；测试连接会发送一条简短消息。更新预设不会自动重译已有歌词。\n\nAPI Key 由 Android Keystore 加密保护，排除在备份之外，不进入操作日志。\n\n卸载应用会删除曲库记录、翻译和应用缓存，请保留原始音乐文件。", { dialog = null })
    }
}

@Composable private fun AgentDialog(model: AmiViewModel, onFolder: () -> Unit, onDismiss: () -> Unit) {
    val enabled by model.bridgeEnabled.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismiss, title = { Text("Agent 与自动化") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("应用在前台时，每 3 秒检查交换文件夹。请求需携带令牌，操作记录仅保存在本机。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("启用外部调用", Modifier.weight(1f)); Switch(enabled, { model.bridgeEnabled.value = it; model.repository.prefs.edit().putBoolean("bridgeEnabled", it).apply() })
            }
            TextButton(onFolder) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("选择交换文件夹") }
            TextButton({ scope.launch { token = withContext(Dispatchers.IO) { model.bridge.newToken() } } }) { Text("生成 / 重置令牌") }
            token?.let { value ->
                Text("仅在此处显示一次，请妥善保存。重置后旧令牌失效。", style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(value, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
            listOf(Triple("agentPlayback", "控制播放", true), Triple("agentReorder", "调整曲序", false), Triple("agentTranslation", "发起翻译（会使用 API）", false), Triple("agentCache", "读取缓存统计", false)).forEach { (key, label, default) ->
                var allowed by remember { mutableStateOf(model.repository.prefs.getBoolean(key, default)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(allowed, { allowed = it; model.repository.prefs.edit().putBoolean(key, it).apply() }, enabled = enabled)
                }
            }
            Text("缓存删除始终需要你在应用中确认。令牌不会出现在结果或日志中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), content = content)
}
@Composable private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, selected: String, onChoice: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, title = { Text(title) }, text = { Column { choices.forEach { (value, label) ->
        Row(Modifier.fillMaxWidth().clickable { onChoice(value) }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(selected == value, { onChoice(value) }); Text(label) }
    } } }, confirmButton = { TextButton(onDismiss) { Text("取消") } })
}
@Composable private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, title = { Text(title) }, text = { LazyColumn(Modifier.heightIn(max = 470.dp)) { item { Text(text, style = MaterialTheme.typography.bodyMedium) } } }, confirmButton = { TextButton(onDismiss) { Text("关闭") } })
}
private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
