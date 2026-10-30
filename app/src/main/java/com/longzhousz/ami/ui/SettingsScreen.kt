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
import com.longzhousz.ami.data.OnlineAudioCache
import com.longzhousz.ami.core.isCloudSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SettingsScreen(model: AmiViewModel, onImport: () -> Unit, onNotifications: () -> Unit, onBridgeFolder: () -> Unit, onLyricsFolder: () -> Unit,
    onLocalLibrary: () -> Unit = {}, onListening: () -> Unit = {}) {
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
    val configured by model.cloud.configured.collectAsStateWithLifecycle()
    val audioCacheLimit by repo.audioCacheLimit.collectAsStateWithLifecycle()
    val signIn = rememberNeteaseLogin(model)
    val scope = rememberCoroutineScope()
    LaunchedEffect(importState.running) {
        cache = withContext(Dispatchers.IO) { repo.cacheInfo() }
        lyricCount = withContext(Dispatchers.IO) { repo.db.lyricsCount() }
        roots = repo.roots(); lyricRoots = repo.lyricRoots()
    }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 30.dp)) {
        item {
            if (model.cloud.direct) {
                SectionLabel("账号")
                SettingsGroup {
                    SettingRow(Icons.Rounded.PersonOutline, "网易云账号", if (configured) "已登录" else "登录后同步专辑、歌单与红心歌曲", {
                        if (configured) dialog = "account" else signIn()
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup {
                SettingRow(Icons.Rounded.Insights, "我的聆听", "周、月、年 · 回看音乐留下的足迹", onListening)
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
            }
            SectionLabel("本地音乐")
            SettingsGroup {
                SettingRow(Icons.Rounded.LibraryMusic, "浏览本地音乐", "保留已导入的曲库与收藏", onLocalLibrary)
                SettingRow(Icons.Rounded.CreateNewFolder, "导入音乐", "音频、LRC、CUE 与经典 NCM", onImport)
                SettingRow(Icons.Rounded.Sync, "重新扫描", "${roots.size} 个已授权文件夹 · ${songs.size} 首歌曲", { if (roots.isNotEmpty()) repo.scan() else onImport() }, trailing = {
                    if (importState.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ChevronRight, null)
                })
                SettingRow(Icons.Rounded.FolderOpen, "管理音乐文件夹", "只读取你选择的文件夹", { roots = repo.roots(); dialog = "roots" })
                SettingRow(Icons.Rounded.NotificationsNone, "导入进度通知", "允许后台导入时显示进度", onNotifications)
                if (importState.message.isNotEmpty() && !importState.running) SettingRow(Icons.Rounded.FactCheck, "最近导入结果", importState.message, { dialog = "import" })
            }
            SectionLabel("存储与隐私")
            SettingsGroup {
                SettingRow(Icons.Rounded.LibraryMusic, "常听歌曲缓存上限", cacheLimitLabel(audioCacheLimit), { dialog = "audioCacheLimit" })
                SettingRow(Icons.Rounded.Storage, "缓存管理", "${megabytes(cache.bytes)} · ${cache.files} 个文件", { scope.launch { cache = withContext(Dispatchers.IO) { repo.cacheInfo() }; dialog = "cache" } })
                SettingRow(Icons.Rounded.SmartToy, "Agent 与自动化", "默认关闭 · 仅处理授权文件夹中的请求", { dialog = "agent" })
                SettingRow(Icons.Rounded.History, "操作记录", "最近 50 条 · 仅保存在本机", { scope.launch { logs = withContext(Dispatchers.IO) { repo.db.actionLog() }; dialog = "logs" } })
                SettingRow(Icons.Rounded.Security, "隐私说明", "本地曲库与网易云连接", { dialog = "privacy" })
            }
            Text("AMI ${com.longzhousz.ami.BuildConfig.VERSION_NAME}", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (dialog) {
        "audioCacheLimit" -> ChoiceDialog("常听歌曲缓存上限", OnlineAudioCache.LIMITS.map { it.toString() to cacheLimitLabel(it) },
            audioCacheLimit.toString(), { repo.setAudioCacheLimit(it.toLong()); dialog = null }, { dialog = null })
        "account" -> GlassAlertDialog({ dialog = null }, title = { Text("网易云账号") }, text = { Text("可以重新登录或退出当前账号。") },
            confirmButton = { TextButton({ dialog = null; signIn() }) { Text("重新登录") } },
            dismissButton = { TextButton({ scope.launch {
                try {
                    model.cancelCloudPreparation()
                    if (model.findSong(model.player.state.value.id)?.isCloudSong() == true) model.player.pause()
                    model.resetOnlineLibrary(); model.cloud.disconnect(); dialog = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { model.message.value = "退出登录未完成，请重试" }
            } }) { Text("退出登录") } })
        "neteaseLyrics" -> GlassAlertDialog({ dialog = null }, title = { Text("网易云歌词") }, text = {
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
            GlassAlertDialog({ dialog = null }, title = { Text("DeepSeek API Key") }, text = {
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
            GlassAlertDialog({ dialog = null }, title = { Text("翻译风格") }, text = {
                Column { Text("每行一个要求，越靠前优先级越高。修改风格后会生成新的翻译。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft, { if (it.length <= 3000) draft = it }, Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 6, maxLines = 10)
                    TextButton({ draft = TranslationClient.DEFAULT_STYLE }) { Text("恢复默认风格") }
                }
            }, confirmButton = { TextButton({ model.setTranslationStyle(draft); dialog = null }, enabled = draft.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ dialog = null }) { Text("取消") } })
        }
        "roots" -> GlassAlertDialog({ dialog = null }, title = { Text("音乐文件夹") }, text = {
            LazyColumn { if (roots.isEmpty()) item { Text("还没有添加文件夹") }
                roots.forEach { root -> item { Column(Modifier.padding(vertical = 8.dp)) {
                    Text(Uri.decode(Uri.parse(root).lastPathSegment.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ repo.forgetRoot(root); roots = repo.roots() }) { Text("停止扫描此文件夹") }
                } } }
                item { Text("停止扫描后，已导入的音乐仍会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }, confirmButton = { TextButton({ dialog = null; onImport() }) { Text("添加文件夹") } }, dismissButton = { TextButton({ dialog = null }) { Text("完成") } })
        "import" -> InfoDialog("最近导入结果", importState.message + if (importState.errors.isEmpty()) "\n\n没有发现问题。" else "\n\n" + importState.errors.joinToString("\n"), { dialog = null })
        "cache" -> GlassAlertDialog({ dialog = null }, title = { Text("缓存管理") }, text = { Text("音乐转换与封面缓存：${megabytes(cache.bytes)}\n可安全清理：${megabytes(cache.reclaimable)}\n\n只清理未被曲库引用的应用缓存。正在使用的音频缓存和原始音乐都会保留。") },
            confirmButton = { TextButton({ scope.launch { runCatching { withContext(Dispatchers.IO) { repo.cleanup() } }.onSuccess { model.message.value = "已清理 ${megabytes(it)}" }.onFailure { model.message.value = it.message }; dialog = null } }, enabled = !importState.running && cache.reclaimable > 0) { Text("清理未引用缓存") } }, dismissButton = { TextButton({ dialog = null }) { Text("关闭") } })
        "logs" -> InfoDialog("操作记录", logs.joinToString("\n\n").ifBlank { "还没有操作记录" }, { dialog = null })
        "agent" -> AgentDialog(model, onBridgeFolder, { dialog = null })
        "privacy" -> InfoDialog("隐私说明", "AMI 只读取你授权的音乐与歌词文件夹，不改写原始文件。NCM 缺图时可获取其元数据指定的 HTTPS 封面。\n\n应用内网易云播放需要你在本应用的官方 HTTPS 网页自行登录，并点击“完成登录”。AMI 只保存本次会话，使用 Android Keystore 加密，用于直接向网易云读取歌单、搜索、歌词和有权限的播放地址；不读取其他应用的密码或登录数据，不向第三方代理发送会话。退出登录会删除保存的会话。\n\n旧版手机随播由网易云 App 播放。经你批准的通知使用权，AMI 只读取网易云的媒体会话信息、封面和播放进度，并发送你点击的播放控制；不读取或保存聊天等普通通知正文。\n\n随播歌词优先使用已导入的本地内容，缺少时向 LRCLIB 发送歌名、歌手、专辑和时长以匹配歌词，不发送音频或网易云登录凭据。AMI 不缓存随播音频；网易云自己的缓存由其设置管理。未找到歌词不影响播放。\n\n主动翻译时只向 DeepSeek 发送歌词与翻译要求；模型预设更新只获取模型列表，不自动重译。API Key 使用 Android Keystore 加密，排除在备份之外，不进入操作记录。\n\n卸载会删除本包的曲库记录、译文、授权与缓存，请保留原始文件。" + if (com.longzhousz.ami.BuildConfig.BUILD_TYPE=="cloudPreview") "\n\n电脑连接试验包的搜索、歌单和播放请求经你选择的电脑连接端发送到网易云，开放平台凭证只在电脑保存，连接码在 Android 加密保存。" else "", { dialog = null })
    }
}

@Composable private fun AgentDialog(model: AmiViewModel, onFolder: () -> Unit, onDismiss: () -> Unit) {
    val enabled by model.bridgeEnabled.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GlassAlertDialog(onDismiss, title = { Text("Agent 与自动化") }, text = {
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
    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(24.dp)), content = content)
}
@Composable private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, selected: String, onChoice: (String) -> Unit, onDismiss: () -> Unit) {
    GlassAlertDialog(onDismiss, title = { Text(title) }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { choices.forEach { (value, label) ->
        Row(Modifier.fillMaxWidth().clickable { onChoice(value) }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(selected == value, { onChoice(value) }); Text(label) }
    } } }, confirmButton = { TextButton(onDismiss) { Text("取消") } })
}
@Composable private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    GlassAlertDialog(onDismiss, title = { Text(title) }, text = { LazyColumn(Modifier.heightIn(max = 470.dp)) { item { Text(text, style = MaterialTheme.typography.bodyMedium) } } }, confirmButton = { TextButton(onDismiss) { Text("关闭") } })
}
private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
private fun cacheLimitLabel(bytes: Long) = if (bytes >= 1024L * 1024 * 1024) "${bytes / (1024L * 1024 * 1024)} GB" else "${bytes / (1024 * 1024)} MB"
