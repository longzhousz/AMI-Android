package com.longzhousz.ami.ui

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.CloudMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable fun CloudMusicScreen(model: AmiViewModel) = CloudMusicContent(model.cloud, model::playCloud, onDisconnect = {
    if (model.findSong(model.player.state.value.id)?.isCloudSong() == true) model.player.pause()
})

@Composable internal fun CloudMusicContent(cloud: CloudMusic, onPlay: (List<CloudTrack>, Int) -> Unit, onDisconnect: () -> Unit = {}) {
    val configured by cloud.configured.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<CloudPlaylist?>(null) }
    var playlists by remember { mutableStateOf<List<CloudPlaylist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<CloudTrack>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var next by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showingTracks = selected != null || submitted.isNotBlank()
    LaunchedEffect(configured, selected?.id, submitted, offset, refresh) {
        if (!configured) { playlists = emptyList(); tracks = emptyList(); return@LaunchedEffect }
        loading = true; error = null
        if (offset == 0) tracks = emptyList()
        try {
            if (!showingTracks) { nickname = cloud.profile(); playlists = cloud.playlists(); more = false }
            else {
                val result = selected?.let { cloud.tracks(it.id, offset) } ?: cloud.search(submitted, offset)
                tracks = (if (offset == 0) emptyList() else tracks).plus(result.tracks).distinctBy { it.id }
                next = result.nextOffset; more = result.more
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message?.take(150) ?: "网易云连接暂不可用" }
        finally { if (currentCoroutineContext().isActive) loading = false }
    }
    androidx.activity.compose.BackHandler(selected != null || submitted.isNotBlank()) { selected = null; submitted = ""; query = ""; offset = 0 }
    Column(Modifier.fillMaxSize().testTag("cloud-page")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showingTracks) ActionIcon(Icons.Rounded.ArrowBack, "返回网易云歌单", { selected = null; submitted = ""; query = ""; offset = 0 })
            Column(Modifier.weight(1f)) {
                Text(selected?.title ?: if (submitted.isNotBlank()) "搜索结果" else "网易云", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (configured && !showingTracks && nickname.isNotEmpty()) Text(nickname, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionIcon(Icons.Rounded.Link, "网易云连接", { pairing = true })
        }
        if (!configured) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.CloudQueue, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("连接你的网易云音乐", style = MaterialTheme.typography.titleLarge)
                    Text("在 Windows 连接端完成登录，再将连接地址粘贴到这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ pairing = true }, Modifier.testTag("cloud-connect")) { Text("连接网易云") }
                }
            }
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("cloud-search"),
                placeholder = { Text("搜索网易云歌曲、艺人") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus(); selected = null; submitted = query.trim(); offset = 0; refresh++
                }), trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "清空网易云搜索", { query = ""; submitted = ""; selected = null; offset = 0 }) })
            if (error != null) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                TextButton({ refresh++ }) { Text("重试") }
            }
            if (loading && tracks.isEmpty() && (showingTracks || playlists.isEmpty())) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.weight(1f).testTag("cloud-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                if (!showingTracks) {
                    item { Text("我的歌单", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 12.dp)) }
                    items(playlists, key = { it.id }) { playlist ->
                        Row(Modifier.fillMaxWidth().clickable { selected = playlist; submitted = ""; offset = 0 }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(playlist.cover, playlist.title, Modifier.size(62.dp), cornerRadius = 14.dp)
                            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
                                Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text("${playlist.count} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (playlists.isEmpty() && error == null) item { Text("还没有歌单", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(tracks, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (track.available) onPlay(tracks, tracks.indexOfFirst { it.id == track.id })
                            else try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("orpheus://song/${track.originalId}?autoplay=1")).setPackage("com.netease.cloudmusic")) }
                            catch (_: android.content.ActivityNotFoundException) { error = "这首歌可在网易云音乐 App 中收听，请先安装该应用" }
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(track.cover, track.album, Modifier.size(50.dp)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).alpha(if (track.available) 1f else .55f)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist.ifBlank { track.album }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (track.available) Text(durationLabel(track.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Icon(Icons.Rounded.OpenInNew, "在网易云音乐中收听", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (tracks.isEmpty() && error == null) item { Text("没有找到歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (more) item { TextButton({ offset = next }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "正在载入" else "更多歌曲") } }
                }
            }
        }
    }
    if (pairing) {
        var address by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var pairError by remember { mutableStateOf<String?>(null) }
        val connect: () -> Unit = { scope.launch {
            busy = true; pairError = null
            try { cloud.connect(address); pairing = false; offset = 0; selected = null; submitted = ""; refresh++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { pairError = failure.message?.take(150) ?: "连接失败，请检查地址和网络" }
            finally { busy = false }
        } }
        val localPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connect()
            else { busy = false; pairError = "连接电脑需要局域网权限，请允许附近设备访问后重试" }
        }
        AlertDialog(onDismissRequest = { if (!busy) pairing = false }, title = { Text("连接网易云") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("复制 Windows 连接端显示的连接地址。手机和电脑需在同一可信局域网，电脑窗口需保持打开。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(address, { address = it }, Modifier.testTag("cloud-address"), label = { Text("电脑端连接地址") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy)
                pairError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (configured) TextButton({ scope.launch { onDisconnect(); cloud.disconnect(); pairing = false } }, enabled = !busy) { Text("断开连接") }
            }
        }, confirmButton = { TextButton({
            try {
                CloudConnection.parse(address)
                if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                    busy = true; pairError = null; localPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else connect()
            } catch (failure: Exception) { pairError = failure.message?.take(150) ?: "请完整复制连接地址" }
        }, enabled = address.isNotBlank() && !busy, modifier = Modifier.testTag("cloud-pair-confirm")) { Text(if (busy) "连接中" else "连接") } },
            dismissButton = { TextButton({ pairing = false }, enabled = !busy) { Text("取消") } })
    }
}
