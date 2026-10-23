package com.longzhousz.ami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.Album
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.albumsOf
import com.longzhousz.ami.core.durationLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AmiApp(model: AmiViewModel, onImport: () -> Unit, onNotifications: () -> Unit, onBridgeFolder: () -> Unit,
    onLyricsFolder: () -> Unit, onLyricFile: (Song) -> Unit) {
    val theme by model.theme.collectAsStateWithLifecycle()
    AmiTheme(theme) {
        val songs by model.repository.songs.collectAsStateWithLifecycle()
        val loading by model.repository.loading.collectAsStateWithLifecycle()
        val playback by model.player.state.collectAsStateWithLifecycle()
        val importing by model.repository.importState.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val albums = remember(songs) { albumsOf(songs) }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
        var fullPlayer by rememberSaveable { mutableStateOf(false) }
        var queue by rememberSaveable { mutableStateOf(false) }
        var remove by remember { mutableStateOf<Song?>(null) }
        val pageState = rememberSaveableStateHolder()
        val current = songs.find { it.id == playback.id }
        val selectedAlbum = albums.find { it.key == albumKey }
        val bridgeAlbum by model.bridgeAlbum.collectAsStateWithLifecycle()
        val bridgeEnabled by model.bridgeEnabled.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(bridgeAlbum) { bridgeAlbum?.let { albumKey = it; model.bridgeAlbum.value = null } }
        LaunchedEffect(bridgeEnabled, lifecycle) {
            if (bridgeEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { model.processBridge(); delay(3000) }
            }
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (model.message.value == it) model.message.value = null } }
        LaunchedEffect(playback.error) { playback.error?.let { snackbar.showSnackbar(it) } }
        BackHandler(selectedAlbum != null && !fullPlayer && !queue) { albumKey = null }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    if (current != null) MiniPlayer(current, playback.playing, playback.buffering, playback.position, playback.duration,
                        onOpen = { fullPlayer = true }, onToggle = { model.act("player.toggle") { model.player.toggle() } }, onNext = { model.act("player.next") { model.player.next() } })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        listOf(Triple("曲库", Icons.Rounded.Album, 0), Triple("我喜欢", Icons.Rounded.FavoriteBorder, 1), Triple("设置", Icons.Rounded.Tune, 2)).forEach { (label, icon, index) ->
                            NavigationBarItem(page == index, { page = index; albumKey = null }, { Icon(icon, label) }, label = { Text(label) })
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(importing.running) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${importing.done}/${importing.total}  ${importing.message}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            TextButton({ model.repository.cancelScan() }) { Text("取消") }
                        }
                        if (importing.total > 0) LinearProgressIndicator(progress = { importing.done.toFloat() / importing.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                when {
                    selectedAlbum != null -> AlbumScreen(selectedAlbum, playback.id, model, { albumKey = null }, { remove = it })
                    page == 2 -> SettingsScreen(model, onImport, onNotifications, onBridgeFolder, onLyricsFolder)
                    else -> pageState.SaveableStateProvider("library-$page") {
                        LibraryScreen(songs, albums, loading, page == 1, playback.id, { page = 2 },
                            onAlbum = { albumKey = it.key }, onPlay = { list, index -> model.play(list, index) }, onFavorite = model::favorite, onRemove = { remove = it })
                    }
                }
            }
        }
        if (fullPlayer && current != null) Dialog({ fullPlayer = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize()) {
                PlayerScreen(current, model, { fullPlayer = false }, { queue = true }, { onLyricFile(current) })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
            }
        }
        if (queue) ModalBottomSheet({ queue = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text("播放队列 · ${playback.queue.size}", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp).navigationBarsPadding()) {
                items(playback.queue.withIndex().toList(), key = { "${it.index}:${it.value}" }) { (index, id) ->
                    songs.find { it.id == id }?.let { song -> SongRow(song, playback.id == id, { model.player.jump(index); queue = false }, { model.favorite(song) }, number = index + 1) }
                }
            }
        }
        remove?.let { song ->
            AlertDialog({ remove = null }, title = { Text("从曲库移除？") }, text = { Text("《${song.title}》将从 AMI 曲库和播放队列中移除。原始音频会保留，重新扫描可再次导入。") },
                confirmButton = { TextButton({ model.remove(song); remove = null }) { Text("移除") } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } })
        }
    }
}

@Composable private fun LibraryScreen(
    songs: List<Song>, albums: List<Album>, loading: Boolean, favorites: Boolean, currentId: String?, onSettings: () -> Unit,
    onAlbum: (Album) -> Unit, onPlay: (List<Song>, Int) -> Unit, onFavorite: (Song) -> Unit, onRemove: (Song) -> Unit,
) {
    var query by rememberSaveable(favorites) { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var recent by rememberSaveable { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val filteredSongs = remember(songs, query, favorites, recent) {
        val filtered = songs.filter { (!favorites || it.favorite) && "${it.title} ${it.album} ${it.artist}".contains(query.trim(), true) }
        if (recent) filtered.sortedByDescending { it.importedAt } else filtered
    }
    val filteredAlbums = remember(albums, query, recent) {
        val filtered = albums.filter { "${it.title} ${it.artist} ${it.songs.joinToString { s -> s.title }}".contains(query.trim(), true) }
        if (recent) filtered.sortedByDescending { it.songs.maxOf { song -> song.importedAt } } else filtered
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("library-search"), placeholder = { Text("搜索专辑、歌曲、艺人") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "清空搜索", { query = "" }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!favorites) { FilterChip(mode == 0, { mode = 0 }, { Text("专辑") }); FilterChip(mode == 1, { mode = 1 }, { Text("歌曲") }) }
            else Text("${filteredSongs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Box {
                ActionIcon(Icons.Rounded.Sort, "排序", { sorting = true }, tint = if (recent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(sorting, { sorting = false }) {
                    DropdownMenuItem({ Text("默认排序") }, { recent = false; sorting = false }, trailingIcon = { if (!recent) Icon(Icons.Rounded.Check, null) })
                    DropdownMenuItem({ Text("最近导入") }, { recent = true; sorting = false }, trailingIcon = { if (recent) Icon(Icons.Rounded.Check, null) })
                }
            }
            ActionIcon(Icons.Rounded.Shuffle, "随机播放", { onPlay(filteredSongs.shuffled(), 0) }, enabled = filteredSongs.isNotEmpty() && !loading)
        }
        key(recent, query) { when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            songs.isEmpty() && !favorites -> EmptyLibrary(onSettings)
            (favorites || mode == 1) && filteredSongs.isEmpty() || !favorites && mode == 0 && filteredAlbums.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (favorites && query.isEmpty()) "暂无收藏" else "没有找到匹配的音乐", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            favorites || mode == 1 -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                items(filteredSongs, key = { it.id }) { song -> SongRow(song, song.id == currentId, { onPlay(filteredSongs, filteredSongs.indexOf(song)) }, { onFavorite(song) }, { onRemove(song) }) }
            }
            else -> LazyVerticalGrid(GridCells.Adaptive(145.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(filteredAlbums, key = { it.key }) { album ->
                    Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable { onAlbum(album) }) {
                        Cover(album.cover, album.title, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(album.title, Modifier.padding(top = 10.dp, start = 2.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${album.artist} · ${album.songs.size} 首", Modifier.padding(start = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } }
    }
}

@Composable private fun EmptyLibrary(onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无音乐", style = MaterialTheme.typography.titleMedium)
            Text("在设置中添加音乐文件夹", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onSettings) { Text("前往设置") }
        }
    }
}

@Composable private fun MiniPlayer(song: Song, playing: Boolean, buffering: Boolean, position: Long, duration: Long, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("mini-player").padding(horizontal = 12.dp, vertical = 5.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpen), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Column {
            Row(Modifier.padding(start = 9.dp, top = 7.dp, bottom = 7.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(song.cover, song.album, Modifier.size(46.dp)); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                if (buffering) CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp)
                ActionIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", onToggle)
                ActionIcon(Icons.Rounded.SkipNext, "下一首", onNext)
            }
            LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {})
        }
    }
}

@Composable private fun AlbumScreen(album: Album, currentId: String?, model: AmiViewModel, onBack: () -> Unit, onRemove: (Song) -> Unit) {
    var reorder by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Rounded.ArrowBack, "返回曲库", onBack)
            Spacer(Modifier.weight(1f))
            TextButton({ reorder = true }) { Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp)); Text("曲序") }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(album.cover, album.title, Modifier.size(88.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(album.title, style = MaterialTheme.typography.headlineSmall)
                            Text(album.artist, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${album.songs.size} 首歌曲 · ${durationLabel(album.duration)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ model.play(album.songs) }, Modifier.weight(1f), contentPadding = PaddingValues(14.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("播放") }
                        FilledTonalButton({ model.play(album.songs.shuffled()) }, Modifier.weight(1f), contentPadding = PaddingValues(14.dp)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("随机播放") }
                    }
                }
            }
            items(album.songs, key = { it.id }) { song -> SongRow(song, currentId == song.id, { model.play(album.songs, album.songs.indexOf(song)) }, { model.favorite(song) }, { onRemove(song) }, album.songs.indexOf(song) + 1) }
        }
    }
    if (reorder) ReorderDialog(album, { reorder = false }, { ids -> model.reorder(ids); reorder = false })
}
