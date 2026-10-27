package com.longzhousz.ami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.Album
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.LibrarySnapshot
import com.longzhousz.ami.core.durationLabel
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.playback.PlayerConnection

private data class AppDestination(val page: Int, val albumKey: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AmiApp(model: AmiViewModel, onImport: () -> Unit, onNotifications: () -> Unit, onBridgeFolder: () -> Unit,
    onLyricsFolder: () -> Unit, onLyricFile: (Song) -> Unit) {
    val theme by model.theme.collectAsStateWithLifecycle()
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    AmiTheme(theme, immersive = fullPlayer) {
        val focus = LocalFocusManager.current
        val library by model.repository.library.collectAsStateWithLifecycle()
        val onlineSongs by model.cloud.catalog.collectAsStateWithLifecycle()
        val songs = library.songs
        val loading by model.repository.loading.collectAsStateWithLifecycle()
        val playback by model.player.state.collectAsStateWithLifecycle()
        val importing by model.repository.importState.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val songsById = library.songsById
        val albumsByKey = library.albumsByKey
        var page by rememberSaveable { mutableIntStateOf(0) }
        var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
        var queue by rememberSaveable { mutableStateOf(false) }
        var remove by remember { mutableStateOf<Song?>(null) }
        var miniBounds by remember { mutableStateOf<Rect?>(null) }
        val pageState = rememberSaveableStateHolder()
        val current = songsById[playback.id] ?: onlineSongs[playback.id]
        val selectedAlbum = albumsByKey[albumKey]
        val bridgeAlbum by model.bridgeAlbum.collectAsStateWithLifecycle()
        val bridgeEnabled by model.bridgeEnabled.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(model, lifecycle) {
            try {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.setUiActive(true)
                    try { awaitCancellation() } finally { model.setUiActive(false) }
                }
            } finally { model.setUiActive(false) }
        }
        LaunchedEffect(bridgeAlbum) { bridgeAlbum?.let { albumKey = it; model.bridgeAlbum.value = null } }
        LaunchedEffect(bridgeEnabled, lifecycle) {
            if (bridgeEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { model.processBridge(); delay(3000) }
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val queueSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        LaunchedEffect(current?.id, loading) {
            if (!loading && current == null) { fullPlayer = false; queue = false }
        }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (model.message.value == it) model.message.value = null } }
        LaunchedEffect(playback.error) { playback.error?.let { snackbar.showSnackbar(it) } }
        BackHandler(selectedAlbum != null && !fullPlayer && !queue) { albumKey = null }
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = if (fullPlayer) Modifier.clearAndSetSemantics {} else Modifier,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        if (current != null) MiniPlayer(current, playback, model.player, clockEnabled = !fullPlayer,
                            onBounds = { miniBounds = it }, onOpen = { focus.clearFocus(); fullPlayer = true },
                            onToggle = { model.act("player.toggle") { model.player.toggle() } }, onNext = { model.act("player.next") { model.player.next() } })
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                            listOf(Triple("曲库", Icons.Rounded.Album, 0), Triple("我喜欢", Icons.Rounded.FavoriteBorder, 1), Triple("网易云", Icons.Rounded.CloudQueue, 3), Triple("设置", Icons.Rounded.Tune, 2)).forEach { (label, icon, index) ->
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
                    AnimatedContent(AppDestination(page, albumKey), Modifier.weight(1f), transitionSpec = {
                        AmiMotion.page(if (targetState.page != initialState.page) targetState.page > initialState.page else targetState.albumKey != null)
                    }, label = "app-destination") { destination ->
                        val album = albumsByKey[destination.albumKey]
                        when {
                            album != null -> AlbumScreen(album, playback.id, model, { albumKey = null }, { remove = it })
                            destination.page == 2 -> SettingsScreen(model, onImport, onNotifications, onBridgeFolder, onLyricsFolder)
                            destination.page == 3 -> CloudMusicScreen(model)
                            else -> pageState.SaveableStateProvider("library-${destination.page}") {
                                LibraryScreen(library, loading, destination.page == 1, playback.id, { page = 2 },
                                    onAlbum = { albumKey = it.key }, onPlay = { list, index -> model.play(list, index) }, onFavorite = model::favorite, onRemove = { remove = it })
                            }
                        }
                    }
                }
            }
            if (fullPlayer && current != null) PlayerSheet(miniBounds, { fullPlayer = false }, collapsedContent = {
                MiniPlayer(current, playback, model.player, clockEnabled = false, verticalPadding = 0,
                    onOpen = {}, onToggle = {}, onNext = {})
            }) { close, dragHandle ->
                Box(Modifier.fillMaxSize()) {
                    PlayerScreen(current, model, close, { queue = true }, { onLyricFile(current) }, dragHandle)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
                }
            }
            if (queue) ModalBottomSheet({ queue = false }, sheetState = queueSheet) {
                Text("播放队列 · ${playback.queue.size}", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp).navigationBarsPadding()) {
                    itemsIndexed(playback.queue, key = { index, id -> "$index:$id" }) { index, id ->
                        (songsById[id] ?: onlineSongs[id])?.let { song -> SongRow(song, playback.id == id, {
                            model.act("player.jump") { model.player.jump(index) }
                            scope.launch { queueSheet.hide(); queue = false }
                        }, { model.favorite(song) }, number = index + 1, modifier = Modifier.animateItem()) }
                    }
                }
            }
            remove?.let { song ->
                AlertDialog({ remove = null }, title = { Text("从曲库移除？") }, text = { Text("《${song.title}》将从 AMI 曲库和播放队列中移除。原始音频会保留，重新扫描可再次导入。") },
                    confirmButton = { TextButton({ model.remove(song); remove = null }) { Text("移除") } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } })
            }
        }
    }
}

@Composable private fun LibraryScreen(
    library: LibrarySnapshot, loading: Boolean, favorites: Boolean, currentId: String?, onSettings: () -> Unit,
    onAlbum: (Album) -> Unit, onPlay: (List<Song>, Int) -> Unit, onFavorite: (Song) -> Unit, onRemove: (Song) -> Unit,
) {
    val songs = library.songs
    var query by rememberSaveable(favorites) { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var recent by rememberSaveable { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val songList = rememberLazyListState()
    val albumGrid = rememberLazyGridState()
    var previousQuery by rememberSaveable { mutableStateOf(query) }
    var previousSorting by rememberSaveable { mutableStateOf(recent) }
    LaunchedEffect(query, recent) {
        if (query != previousQuery || recent != previousSorting) {
            previousQuery = query
            previousSorting = recent
            songList.scrollToItem(0)
            albumGrid.scrollToItem(0)
        }
    }
    val initial = remember(library, favorites) { library.search("", favorites, false) }
    val searched by produceState(initial, library, query, favorites, recent) {
        value = withContext(Dispatchers.Default) { library.search(query, favorites, recent) }
    }
    val results = if (query.isBlank() && !recent) initial else searched
    val filteredSongs = results.songs
    val filteredAlbums = results.albums
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
        AnimatedContent(if (favorites) 1 else mode, Modifier.weight(1f), transitionSpec = { AmiMotion.page(targetState > initialState) }, label = "library-layout") { layout ->
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            songs.isEmpty() && !favorites -> EmptyLibrary(onSettings)
            layout == 1 && filteredSongs.isEmpty() || layout == 0 && filteredAlbums.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (favorites && query.isEmpty()) "暂无收藏" else "没有找到匹配的音乐", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            layout == 1 -> LazyColumn(state = songList, modifier = Modifier.testTag("library-song-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                itemsIndexed(filteredSongs, key = { _, song -> song.id }) { index, song ->
                    SongRow(song, song.id == currentId, { onPlay(filteredSongs, index) }, { onFavorite(song) }, { onRemove(song) },
                        modifier = Modifier.animateItem(placementSpec = spring(stiffness = 500f), fadeInSpec = tween(160), fadeOutSpec = tween(120)))
                }
            }
            else -> LazyVerticalGrid(GridCells.Adaptive(145.dp), state = albumGrid, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(filteredAlbums, key = { it.key }) { album ->
                    Column(Modifier.animateItem(placementSpec = spring(stiffness = 500f)).clip(RoundedCornerShape(18.dp)).clickable { onAlbum(album) }) {
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

@Composable private fun MiniPlayer(
    song: Song, playback: PlaybackState, player: PlayerConnection,
    onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    clockEnabled: Boolean = true, onBounds: (Rect) -> Unit = {}, verticalPadding: Int = 5,
) {
    val clock = rememberPlaybackClock(player, playback, clockEnabled)
    val duration = playback.duration.takeIf { it > 0 } ?: song.duration
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = verticalPadding.dp)
        .onGloballyPositioned { onBounds(it.boundsInRoot()) }.testTag("mini-player")
        .clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpen), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Column {
            Row(Modifier.padding(start = 9.dp, top = 7.dp, bottom = 7.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(song.cover, song.album, Modifier.size(46.dp)); Spacer(Modifier.width(12.dp))
                AnimatedContent(song.title to song.artist, Modifier.weight(1f), transitionSpec = { AmiMotion.page(true) }, label = "mini-track") { track ->
                    Column { Text(track.first, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
                if (playback.buffering) CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp)
                ActionIcon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "暂停" else "播放", onToggle)
                ActionIcon(Icons.Rounded.SkipNext, "下一首", onNext)
            }
            LinearProgressIndicator(progress = { if (duration > 0) (clock.value.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {})
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
            itemsIndexed(album.songs, key = { _, song -> song.id }) { index, song ->
                SongRow(song, currentId == song.id, { model.play(album.songs, index) }, { model.favorite(song) }, { onRemove(song) }, index + 1,
                    modifier = Modifier.animateItem(placementSpec = spring(stiffness = 500f)))
            }
        }
    }
    if (reorder) ReorderDialog(album, { reorder = false }, { ids -> model.reorder(ids); reorder = false })
}
