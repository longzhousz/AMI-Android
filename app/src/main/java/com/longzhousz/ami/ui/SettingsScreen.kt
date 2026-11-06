package com.longzhousz.ami.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.R
import com.longzhousz.ami.core.isCloudSong
import com.longzhousz.ami.data.LibraryRepository
import com.longzhousz.ami.data.OnlineAudioCache
import com.longzhousz.ami.data.TranslationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SettingsScreen(
    model: AmiViewModel,
    onImport: () -> Unit,
    onNotifications: () -> Unit,
    onBridgeFolder: () -> Unit,
    onLyricsFolder: () -> Unit,
    onLocalLibrary: () -> Unit = {},
    onListening: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    settingsVisible: Boolean = true,
) {
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
    val backupBusy by model.library.backupBusy.collectAsStateWithLifecycle()
    val exportBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) model.library.exportData(uri)
        }
    val restoreBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.library.restoreData(uri)
        }
    val signIn = rememberNeteaseLogin(model)
    val scope = rememberCoroutineScope()
    UpdatePrompt(
        (LocalContext.current.applicationContext as com.longzhousz.ami.AmiApplication).updates,
        settingsVisible && dialog == null,
        onInstallUpdate,
    )
    LaunchedEffect(importState.running) {
        cache = withContext(Dispatchers.IO) { repo.cacheInfo() }
        lyricCount = withContext(Dispatchers.IO) { repo.db.lyricsCount() }
        roots = repo.roots()
        lyricRoots = repo.lyricRoots()
    }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 30.dp)) {
        item {
            if (model.cloud.direct) {
                SectionLabel(stringResource(R.string.settings_account_section))
                SettingsGroup {
                    SettingRow(Icons.Rounded.PersonOutline, stringResource(R.string.settings_account), if (configured) stringResource(R.string.settings_logged_in) else stringResource(R.string.settings_sign_in_summary), {
                        if (configured) dialog = "account" else signIn()
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup {
                SettingRow(Icons.Rounded.Insights, stringResource(R.string.settings_listening), stringResource(R.string.settings_listening_summary), onListening)
            }
            SectionLabel(stringResource(R.string.settings_translation_section))
            SettingsGroup {
                SettingRow(Icons.Rounded.Lyrics, stringResource(R.string.settings_netease_lyrics), "$lyricCount 份歌词 · 按歌曲 ID 自动关联", {
                    lyricRoots = repo.lyricRoots()
                    dialog = "neteaseLyrics"
                })
                SettingRow(Icons.Rounded.Key, "DeepSeek API Key", if (savedKey) stringResource(R.string.settings_saved_key) else stringResource(R.string.settings_key_summary), { dialog = "key" })
                SettingRow(Icons.Rounded.AutoAwesome, stringResource(R.string.settings_model), modelState.summary, {
                    model.refreshModels()
                    dialog = "model"
                })
                SettingRow(Icons.Rounded.EditNote, stringResource(R.string.settings_style), stringResource(R.string.settings_style_summary), { dialog = "style" })
                SettingRow(Icons.Rounded.NetworkCheck, stringResource(R.string.settings_test), stringResource(R.string.settings_test_summary), { model.testConnection() })
            }
            SectionLabel(stringResource(R.string.settings_appearance_section))
            SettingsGroup {
                SettingRow(
                    Icons.Rounded.Palette,
                    stringResource(R.string.settings_theme),
                    when (theme) {
                        "light" -> stringResource(R.string.settings_light)
                        "dark" -> stringResource(R.string.settings_dark)
                        else -> stringResource(R.string.settings_system)
                    },
                    { dialog = "theme" },
                )
            }
            SectionLabel(stringResource(R.string.settings_local_section))
            SettingsGroup {
                SettingRow(Icons.Rounded.LibraryMusic, stringResource(R.string.settings_local_browse), stringResource(R.string.settings_local_summary), onLocalLibrary)
                SettingRow(Icons.Rounded.CreateNewFolder, stringResource(R.string.settings_import), "音频、LRC、CUE 与经典 NCM", onImport)
                SettingRow(Icons.Rounded.Sync, stringResource(R.string.settings_scan), "${roots.size} 个已授权文件夹 · ${songs.size} 首歌曲", { if (roots.isNotEmpty()) repo.scan() else onImport() }, trailing = {
                    if (importState.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ChevronRight, null)
                })
                SettingRow(Icons.Rounded.FolderOpen, stringResource(R.string.settings_folders), stringResource(R.string.settings_folder_summary), {
                    roots = repo.roots()
                    dialog = "roots"
                })
                SettingRow(Icons.Rounded.NotificationsNone, "导入进度通知", "允许后台导入时显示进度", onNotifications)
                if (importState.message.isNotEmpty() && !importState.running) SettingRow(Icons.Rounded.FactCheck, "最近导入结果", importState.message, { dialog = "import" })
            }
            SectionLabel(stringResource(R.string.settings_storage_section))
            SettingsGroup {
                SettingRow(Icons.Rounded.SaveAlt, stringResource(R.string.backup_title), stringResource(R.string.backup_summary), {
                    if (!backupBusy && !importState.running) dialog = "backup"
                })
                SettingRow(Icons.Rounded.LibraryMusic, stringResource(R.string.settings_cache_limit), cacheLimitLabel(audioCacheLimit), { dialog = "audioCacheLimit" })
                SettingRow(Icons.Rounded.Storage, stringResource(R.string.settings_cache), "${megabytes(cache.bytes)} · ${cache.files} 个文件", {
                    scope.launch {
                        cache = withContext(Dispatchers.IO) { repo.cacheInfo() }
                        dialog = "cache"
                    }
                })
                SettingRow(Icons.Rounded.SmartToy, stringResource(R.string.settings_agent), stringResource(R.string.settings_agent_summary), { dialog = "agent" })
                SettingRow(Icons.Rounded.History, stringResource(R.string.settings_history), stringResource(R.string.settings_history_summary), {
                    scope.launch {
                        logs = withContext(Dispatchers.IO) { repo.db.actionLog() }
                        dialog = "logs"
                    }
                })
                SettingRow(Icons.Rounded.Security, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_summary), { dialog = "privacy" })
            }
            Text("AMI ${com.longzhousz.ami.BuildConfig.VERSION_NAME}", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (dialog) {
        "backup" -> {
            GlassAlertDialog(
                { dialog = null },
                title = { Text(stringResource(R.string.backup_title)) },
                text = { Text(stringResource(R.string.backup_description)) },
                confirmButton = {
                    TextButton({
                        dialog = null
                        exportBackup.launch("AMI-backup-${java.time.LocalDate.now()}.json")
                    }, enabled = !backupBusy && !importState.running) { Text(stringResource(R.string.backup_export)) }
                },
                dismissButton = {
                    TextButton({
                        dialog = null
                        restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }, enabled = !backupBusy && !importState.running) { Text(stringResource(R.string.backup_restore)) }
                },
            )
        }

        "audioCacheLimit" -> {
            ChoiceDialog(
                stringResource(R.string.settings_cache_limit),
                OnlineAudioCache.LIMITS.map { it.toString() to cacheLimitLabel(it) },
                audioCacheLimit.toString(),
                {
                    repo.setAudioCacheLimit(it.toLong())
                    dialog = null
                },
                { dialog = null },
            )
        }

        "account" -> {
            GlassAlertDialog(
                { dialog = null },
                title = { Text(stringResource(R.string.settings_account)) },
                text = { Text(stringResource(R.string.settings_account_actions)) },
                confirmButton = {
                    TextButton({
                        dialog = null
                        signIn()
                    }) { Text(stringResource(R.string.settings_sign_in_again)) }
                },
                dismissButton = {
                    TextButton({
                        scope.launch {
                            try {
                                model.cancelCloudPreparation()
                                if (model.findSong(model.player.state.value.id)?.isCloudSong() == true) model.player.pause()
                                model.resetOnlineLibrary()
                                model.cloud.disconnect()
                                dialog = null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                model.showMessage("退出登录未完成，请重试")
                            }
                        }
                    }) { Text(stringResource(R.string.settings_sign_out)) }
                },
            )
        }

        "neteaseLyrics" -> {
            GlassAlertDialog(
                { dialog = null },
                title = { Text(stringResource(R.string.settings_netease_lyrics)) },
                text = {
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
                            TextButton({
                                repo.forgetLyricRoot(root)
                                lyricRoots = repo.lyricRoots()
                            }) { Text("停止扫描此歌词文件夹") }
                        }
                        if (lyricRoots.isNotEmpty()) {
                            TextButton({
                                repo.scan()
                                dialog = null
                            }, enabled = !importState.running) { Text("重新扫描并匹配歌词") }
                        }
                    }
                },
                confirmButton = {
                    TextButton({
                        dialog = null
                        onLyricsFolder()
                    }, enabled = !importState.running) { Text("选择歌词文件夹") }
                },
                dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.common_done)) } },
            )
        }

        "key" -> {
            var key by remember { mutableStateOf("") }
            GlassAlertDialog({ dialog = null }, title = { Text("DeepSeek API Key") }, text = {
                Column {
                    Text("密钥使用设备密钥加密。AMI 不会显示已保存的密钥。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text("粘贴新的 API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
                    if (savedKey) {
                        TextButton({
                            model.saveKey("")
                            dialog = null
                        }) { Text("删除已保存的密钥") }
                    }
                }
            }, confirmButton = {
                TextButton({
                    model.saveKey(key)
                    key = ""
                    dialog = null
                }, enabled = key.isNotBlank()) { Text("加密保存") }
            }, dismissButton = {
                TextButton({
                    key = ""
                    dialog = null
                }) { Text(stringResource(R.string.common_cancel)) }
            })
        }

        "model" -> {
            TranslationModelDialog(
                modelState,
                savedKey,
                { model.refreshModels(force = true) },
                model::setAutomaticModelUpdates,
                {
                    model.selectTranslationModel(it)
                    dialog = null
                },
                { dialog = null },
            )
        }

        "theme" -> {
            ChoiceDialog(stringResource(R.string.settings_theme), listOf("system" to stringResource(R.string.settings_system), "light" to stringResource(R.string.settings_light), "dark" to stringResource(R.string.settings_dark)), theme, {
                model.setTheme(it)
                dialog = null
            }, { dialog = null })
        }

        "style" -> {
            var draft by remember { mutableStateOf(style) }
            GlassAlertDialog({ dialog = null }, title = { Text(stringResource(R.string.settings_style)) }, text = {
                Column {
                    Text("每行一个要求，越靠前优先级越高。修改风格后会生成新的翻译。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft, { if (it.length <= 3000) draft = it }, Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 6, maxLines = 10)
                    TextButton({ draft = TranslationClient.DEFAULT_STYLE }) { Text("恢复默认风格") }
                }
            }, confirmButton = {
                TextButton({
                    model.setTranslationStyle(draft)
                    dialog = null
                }, enabled = draft.isNotBlank()) { Text("保存") }
            }, dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.common_cancel)) } })
        }

        "roots" -> {
            GlassAlertDialog({ dialog = null }, title = { Text("音乐文件夹") }, text = {
                LazyColumn {
                    if (roots.isEmpty()) item { Text("还没有添加文件夹") }
                    roots.forEach { root ->
                        item {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(Uri.decode(Uri.parse(root).lastPathSegment.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                                TextButton({
                                    repo.forgetRoot(root)
                                    roots = repo.roots()
                                }) { Text("停止扫描此文件夹") }
                            }
                        }
                    }
                    item { Text("停止扫描后，已导入的音乐仍会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }, confirmButton = {
                TextButton({
                    dialog = null
                    onImport()
                }) { Text("添加文件夹") }
            }, dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.common_done)) } })
        }

        "import" -> {
            InfoDialog("最近导入结果", importState.message + if (importState.errors.isEmpty()) "\n\n没有发现问题。" else "\n\n" + importState.errors.joinToString("\n"), { dialog = null })
        }

        "cache" -> {
            GlassAlertDialog(
                { dialog = null },
                title = { Text(stringResource(R.string.settings_cache)) },
                text = { Text("音乐转换与封面缓存：${megabytes(cache.bytes)}\n可安全清理：${megabytes(cache.reclaimable)}\n\n只清理未被曲库引用的应用缓存。正在使用的音频缓存和原始音乐都会保留。") },
                confirmButton = {
                    TextButton({
                        scope.launch {
                            model.library.cleanCache()
                            dialog = null
                        }
                    }, enabled = !importState.running && cache.reclaimable > 0) { Text("清理未引用缓存") }
                },
                dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.common_close)) } },
            )
        }

        "logs" -> {
            InfoDialog(stringResource(R.string.settings_history), logs.joinToString("\n\n").ifBlank { "还没有操作记录" }, { dialog = null })
        }

        "agent" -> {
            AgentDialog(model, onBridgeFolder, { dialog = null })
        }

        "privacy" -> {
            InfoDialog(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_body) + if (com.longzhousz.ami.BuildConfig.BUILD_TYPE == "cloudPreview") "\n\n电脑连接试验包的搜索、歌单和播放请求经你选择的电脑连接端发送到网易云，开放平台凭证只在电脑保存，连接码在 Android 加密保存。" else "", { dialog = null })
        }
    }
}

@Composable private fun AgentDialog(
    model: AmiViewModel,
    onFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enabled by model.bridgeEnabled.collectAsStateWithLifecycle()
    val permissions by model.library.bridgePermissions.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GlassAlertDialog(onDismiss, title = { Text(stringResource(R.string.settings_agent)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("应用在前台时，每 3 秒检查交换文件夹。请求需携带令牌，操作记录仅保存在本机。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("启用外部调用", Modifier.weight(1f))
                Switch(enabled, {
                    model.setBridgeEnabled(it)
                })
            }
            TextButton(onFolder) {
                Icon(Icons.Rounded.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("选择交换文件夹")
            }
            TextButton({ scope.launch { token = withContext(Dispatchers.IO) { model.bridge.newToken() } } }) { Text("生成 / 重置令牌") }
            token?.let { value ->
                Text("仅在此处显示一次，请妥善保存。重置后旧令牌失效。", style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.text.selection
                    .SelectionContainer { Text(value, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
            listOf(Triple("agentPlayback", "控制播放", true), Triple("agentReorder", "调整曲序", false), Triple("agentTranslation", "发起翻译（会使用 API）", false), Triple("agentCache", "读取缓存统计", false)).forEach { (key, label, default) ->
                val allowed = permissions[key] ?: default
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(allowed, {
                        model.library.setBridgePermission(key, it)
                    }, enabled = enabled)
                }
            }
            Text("缓存删除始终需要你在应用中确认。令牌不会出现在结果或日志中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_done)) } })
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(24.dp)), content = content)
}

@Composable private fun ChoiceDialog(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onChoice: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassAlertDialog(onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onChoice(value) }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected == value, { onChoice(value) })
                    Text(label)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable private fun InfoDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    GlassAlertDialog(onDismiss, title = { Text(title) }, text = { LazyColumn(Modifier.heightIn(max = 470.dp)) { item { Text(text, style = MaterialTheme.typography.bodyMedium) } } }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_close)) } })
}

private fun megabytes(bytes: Long) = "%.1f MB".format(java.util.Locale.getDefault(), bytes / 1048576.0)

private fun cacheLimitLabel(bytes: Long) = if (bytes >= 1024L * 1024 * 1024) "${bytes / (1024L * 1024 * 1024)} GB" else "${bytes / (1024 * 1024)} MB"
