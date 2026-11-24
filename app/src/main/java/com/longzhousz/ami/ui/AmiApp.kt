package com.longzhousz.ami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.ListeningScale
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.isCloudSong
import com.longzhousz.ami.core.showLocalFavorites
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.playback.PlayerConnection
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

private data class AppDestination(
    val page: Int,
    val albumKey: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmiApp(
    model: AmiViewModel,
    onImport: () -> Unit,
    onNotifications: () -> Unit,
    onBridgeFolder: () -> Unit,
    onLyricsFolder: () -> Unit,
    onLyricFile: (Song) -> Unit,
    initialPage: Int = 0,
    onInstallUpdate: () -> Unit = {},
) {
    val theme by model.theme.collectAsStateWithLifecycle()
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(if (initialPage == 3 && model.cloud.direct) 0 else initialPage.coerceIn(0, 6)) }
    val library by model.repository.library.collectAsStateWithLifecycle()
    val cloudConfigured by model.cloud.configured.collectAsStateWithLifecycle()
    val loading by model.repository.loading.collectAsStateWithLifecycle()
    var favoritesSource by rememberSaveable { mutableStateOf<String?>(null) }
    val hasLocalMusic = library.songs.isNotEmpty()
    val localFavoritesSelected = showLocalFavorites(model.cloud.direct, hasLocalMusic, loading, cloudConfigured, favoritesSource)
    val immersiveFavorites = page == 1 && model.cloud.direct && !localFavoritesSelected
    val darkTheme = resolveDarkTheme(theme)
    var playerOverStatusBar by remember { mutableStateOf(false) }
    var playerOverNavigationBar by remember { mutableStateOf(false) }
    AmiTheme(
        theme,
        statusBarImmersive = (immersiveFavorites && darkTheme) || (fullPlayer && playerOverStatusBar),
        navigationBarImmersive = (immersiveFavorites && darkTheme) || (fullPlayer && playerOverNavigationBar),
    ) {
        val focus = LocalFocusManager.current
        val onlineLikes by model.cloud.likes.collectAsStateWithLifecycle()
        val albumOrder by model.online.albumOrder.collectAsStateWithLifecycle()
        val playback by model.player.state.collectAsStateWithLifecycle()
        val selection by model.playerSelection.collectAsStateWithLifecycle()
        val importing by model.repository.importState.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val songsById = library.songsById
        val albumsByKey = library.albumsByKey
        var librarySource by rememberSaveable { mutableStateOf("local") }
        val localSelected = hasLocalMusic && librarySource == "local"
        var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
        var queue by rememberSaveable { mutableStateOf(false) }
        var remove by remember { mutableStateOf<Song?>(null) }
        var miniBounds by remember { mutableStateOf<Rect?>(null) }
        val pageState = rememberSaveableStateHolder()
        val selectedSong by model.playback.currentSong.collectAsStateWithLifecycle()
        val current = selectedSong
        // Survives sheet disposal, but every new track starts on its original artwork.
        val lyricsPage = rememberSaveable(playback.id, selection) { mutableStateOf(false) }
        val floatingInset = if (current != null) 156.dp else 88.dp
        var lastNavPage by remember { mutableIntStateOf(-1) }
        var lastNavTap by remember { mutableLongStateOf(0L) }
        var localLibraryRefresh by rememberSaveable { mutableIntStateOf(0) }
        var localFavoritesRefresh by rememberSaveable { mutableIntStateOf(0) }
        var onlineLibraryRefresh by rememberSaveable { mutableIntStateOf(0) }
        var onlineFavoritesRefresh by rememberSaveable { mutableIntStateOf(0) }
        val selectedAlbum = albumsByKey[albumKey]
        val localAlbumInline = (page == 0 && localSelected) || (page == 1 && localFavoritesSelected)
        val bridgeAlbum by model.bridgeAlbum.collectAsStateWithLifecycle()
        val bridgeEnabled by model.bridgeEnabled.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(model, lifecycle) {
            try {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.setUiActive(true)
                    try {
                        awaitCancellation()
                    } finally {
                        model.setUiActive(false)
                    }
                }
            } finally {
                model.setUiActive(false)
            }
        }
        LaunchedEffect(bridgeAlbum) {
            bridgeAlbum?.let {
                albumKey = it
                model.consumeBridgeAlbum(it)
            }
        }
        LaunchedEffect(bridgeEnabled, lifecycle) {
            if (bridgeEnabled) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        model.processBridge()
                        delay(3000)
                    }
                }
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val contentBackdrop = rememberLayerBackdrop()
        val floatingColors = if (immersiveFavorites && darkTheme) FavoritesColors else MaterialTheme.colorScheme
        LaunchedEffect(current?.id, loading) {
            if (!loading && current == null) {
                fullPlayer = false
                queue = false
            }
        }
        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                model.consumeMessage(it)
            }
        }
        val context = LocalContext.current
        LaunchedEffect(playback.id, playback.error) {
            playback.error?.let { error ->
                val report = playback.id?.let(model.cloud::playbackDiagnostics)
                val result =
                    snackbar.showSnackbar(
                        error,
                        actionLabel = if (report != null) "复制诊断" else null,
                        withDismissAction = report != null,
                        duration = if (report != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                if (result == SnackbarResult.ActionPerformed && report != null) copyPlaybackDiagnostics(context, report)
            }
        }
        BackHandler(selectedAlbum != null && !fullPlayer && !queue) { albumKey = null }
        BackHandler(page in 1..2 && selectedAlbum == null && !fullPlayer && !queue) { page = 0 }
        BackHandler(page >= 4 && selectedAlbum == null && !fullPlayer && !queue) { page = 2 }
        LaunchedEffect(page) {
            if (page in 0..1 || page in 4..5) model.repository.load()
            if (page in 4..5) {
                librarySource = "local"
                page = 0
            }
        }

        fun refreshLocalLibrary() {
            if (model.repository.roots().isNotEmpty()) model.repository.scan() else model.showMessage("请先添加音乐文件夹")
        }

        fun navigate(index: Int) {
            page = index
            albumKey = null
            focus.clearFocus()
        }

        fun refreshTab(index: Int) {
            navigate(index)
            when (index) {
                0 -> if (localSelected) localLibraryRefresh++ else onlineLibraryRefresh++
                1 -> if (localFavoritesSelected) localFavoritesRefresh++ else onlineFavoritesRefresh++
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides contentBackdrop) {
            Box(Modifier.fillMaxSize()) {
                GlassBackdropHost(Modifier.fillMaxSize().layerBackdrop(contentBackdrop), background = {
                    MaterialTheme(colorScheme = floatingColors) { GlassCanvas() }
                }) {
                    CompositionLocalProvider(LocalVisualsActive provides !fullPlayer) {
                        Scaffold(
                            modifier = if (fullPlayer) Modifier.clearAndSetSemantics {} else Modifier,
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
                            snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = floatingInset)) },
                        ) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding)) {
                                AnimatedVisibility(importing.running) {
                                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp, vertical = 8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${importing.done}/${importing.total}  ${importing.message}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                            TextButton({ model.repository.cancelScan() }) { Text("取消") }
                                        }
                                        if (importing.total > 0) {
                                            LinearProgressIndicator(progress = { importing.done.toFloat() / importing.total }, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                                AnimatedContent(AppDestination(page, if (localAlbumInline) null else albumKey), Modifier.weight(1f), transitionSpec = {
                                    AmiMotion.page(if (targetState.page != initialState.page) targetState.page > initialState.page else targetState.albumKey != null)
                                }, label = "app-destination") { destination ->
                                    val album = albumsByKey[destination.albumKey]
                                    Box(Modifier.fillMaxSize().then(if ((model.cloud.direct && destination.page in 0..1 && album == null) || album != null) Modifier else Modifier.statusBarsPadding())) {
                                        when {
                                            album != null -> {
                                                val active = destination.albumKey == albumKey
                                                GlassBackdropHost(Modifier.fillMaxSize(), background = { PlayerBackdrop(album.cover) }) {
                                                    LocalAlbumDetail(
                                                        album,
                                                        playback.id,
                                                        { albumKey = null },
                                                        onPlay = { list, index -> model.play(list, index) },
                                                        onFavorite = model::favorite,
                                                        onRemove = { remove = it },
                                                        onReorder = model::reorder,
                                                        bottomInset = floatingInset,
                                                        modifier = if (active) Modifier else Modifier.clearAndSetSemantics {},
                                                        active = active,
                                                    )
                                                }
                                            }

                                            destination.page == 2 -> {
                                                pageState.SaveableStateProvider("settings") {
                                                    Box(Modifier.padding(bottom = floatingInset)) {
                                                        val colors = MaterialTheme.colorScheme
                                                        MaterialTheme(colorScheme = if (darkTheme) colors.copy(primary = Color.White, onSurfaceVariant = Color.White.copy(alpha = .72f)) else colors) {
                                                            SettingsScreen(
                                                                model,
                                                                onImport,
                                                                onNotifications,
                                                                onBridgeFolder,
                                                                onLyricsFolder,
                                                                onListening = { page = 6 },
                                                                onInstallUpdate = onInstallUpdate,
                                                                settingsVisible = page == 2 && !fullPlayer && !queue && selectedAlbum == null && remove == null,
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            destination.page == 6 -> {
                                                Box(Modifier.padding(bottom = floatingInset)) {
                                                    ListeningStatsScreen(
                                                        model.repository.listeningStats,
                                                        { page = 2 },
                                                        ListeningScale.entries.firstOrNull { it.name == model.repository.prefs.getString("listeningScale", "MONTH") } ?: ListeningScale.MONTH,
                                                        {
                                                            model.library.setListeningScale(it)
                                                        },
                                                    )
                                                }
                                            }

                                            destination.page == 1 && model.cloud.direct && !cloudConfigured && loading -> {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                            }

                                            destination.page == 1 && localFavoritesSelected -> {
                                                pageState.SaveableStateProvider("local-favorites") {
                                                    LocalLibraryScreen(
                                                        library,
                                                        loading,
                                                        importing.running,
                                                        playback.id,
                                                        floatingInset,
                                                        selectedAlbum = selectedAlbum,
                                                        onCloud = { favoritesSource = "cloud" },
                                                        onRefresh = ::refreshLocalLibrary,
                                                        refreshRequest = localFavoritesRefresh,
                                                        onAlbum = { albumKey = it.key },
                                                        onBack = { albumKey = null },
                                                        onPlay = { list, index -> model.play(list, index) },
                                                        onFavorite = model::favorite,
                                                        onRemove = { remove = it },
                                                        onReorder = model::reorder,
                                                        favorites = true,
                                                        showSourceSwitch = cloudConfigured,
                                                        active = destination.page == page,
                                                    )
                                                }
                                            }

                                            !model.cloud.direct -> {
                                                CloudMusicScreen(model)
                                            }

                                            destination.page == 0 && loading -> {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                            }

                                            destination.page == 0 && localSelected -> {
                                                pageState.SaveableStateProvider("local-library") {
                                                    LocalLibraryScreen(
                                                        library,
                                                        loading,
                                                        importing.running,
                                                        playback.id,
                                                        floatingInset,
                                                        selectedAlbum = selectedAlbum,
                                                        onCloud = { librarySource = "cloud" },
                                                        onRefresh = ::refreshLocalLibrary,
                                                        refreshRequest = localLibraryRefresh,
                                                        onAlbum = { albumKey = it.key },
                                                        onBack = { albumKey = null },
                                                        onPlay = { list, index -> model.play(list, index) },
                                                        onFavorite = model::favorite,
                                                        onRemove = { remove = it },
                                                        onReorder = model::reorder,
                                                        active = destination.page == page,
                                                    )
                                                }
                                            }

                                            else -> {
                                                pageState.SaveableStateProvider("online-${destination.page}") {
                                                    OnlineLibraryScreen(
                                                        model,
                                                        destination.page == 1,
                                                        floatingInset,
                                                        onImport =
                                                            if (destination.page == 0) {
                                                                (
                                                                    {
                                                                        librarySource = "local"
                                                                        onImport()
                                                                    }
                                                                )
                                                            } else {
                                                                null
                                                            },
                                                        sourceSelector =
                                                            when {
                                                                destination.page == 0 && (hasLocalMusic || cloudConfigured) -> {
                                                                    {
                                                                        LibrarySourceSwitch(
                                                                            false,
                                                                            { librarySource = "local" },
                                                                            {},
                                                                            showLocal = hasLocalMusic,
                                                                            albumOrder = albumOrder,
                                                                            onAlbumOrder = model.online::selectAlbumOrder,
                                                                        )
                                                                    }
                                                                }

                                                                destination.page == 1 && hasLocalMusic && cloudConfigured -> {
                                                                    { LibrarySourceSwitch(false, { favoritesSource = "local" }, {}) }
                                                                }

                                                                else -> {
                                                                    null
                                                                }
                                                            },
                                                        refreshRequest = if (destination.page == 1) onlineFavoritesRefresh else onlineLibraryRefresh,
                                                        active = destination.page == page,
                                                    ) {
                                                        if (page == 0) {
                                                            focus.clearFocus()
                                                            playerOverStatusBar = false
                                                            playerOverNavigationBar = false
                                                            fullPlayer = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                MaterialTheme(colorScheme = floatingColors) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .then(if (fullPlayer) Modifier.clearAndSetSemantics {} else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (current != null) {
                            MiniPlayer(
                                current,
                                playback,
                                model.player,
                                clockEnabled = !fullPlayer,
                                modifier = Modifier.padding(horizontal = 12.dp).graphicsLayer { alpha = if (fullPlayer) 0f else 1f },
                                onBounds = { miniBounds = it },
                                onOpen = {
                                    focus.clearFocus()
                                    playerOverStatusBar = false
                                    playerOverNavigationBar = false
                                    fullPlayer = true
                                },
                                onToggle = { model.act("player.toggle") { model.player.toggle() } },
                                onNext = { model.act("player.next") { model.player.next() } },
                            )
                        }
                        if (current != null) Spacer(Modifier.height(8.dp))
                        GlassSurface(shape = RoundedCornerShape(32.dp), prominent = true) {
                            val tab = if (page >= 2) 2 else page
                            val indicator by animateDpAsState(8.dp + 60.dp * tab, spring(dampingRatio = .78f, stiffness = 450f), label = "glass-tab")
                            Box(Modifier.offset(x = indicator, y = 4.dp).size(52.dp).liquidGlass(CircleShape))
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(Triple("曲库", Icons.Rounded.LibraryMusic, 0), Triple("我喜欢", Icons.Rounded.FavoriteBorder, 1), Triple("设置", Icons.Rounded.Tune, 2)).forEach { (label, icon, index) ->
                                    val active = page == index || (index == 2 && page >= 4)
                                    IconButton(
                                        onClick = {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            val doubleTap = index < 2 && lastNavPage == index && now - lastNavTap <= android.view.ViewConfiguration.getDoubleTapTimeout()
                                            lastNavPage = if (doubleTap) -1 else index
                                            lastNavTap = now
                                            if (doubleTap) refreshTab(index) else navigate(index)
                                        },
                                        modifier =
                                            Modifier
                                                .size(52.dp)
                                                .semantics {
                                                    selected = active
                                                    if (index < 2) {
                                                        customActions =
                                                            listOf(
                                                                CustomAccessibilityAction("刷新$label") {
                                                                    refreshTab(index)
                                                                    true
                                                                },
                                                            )
                                                    }
                                                },
                                    ) {
                                        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                if (fullPlayer && current != null) {
                    PlayerSheet(miniBounds, { fullPlayer = false }, onSystemBars = { status, navigation ->
                        playerOverStatusBar = status
                        playerOverNavigationBar = navigation
                    }, collapsedContent = {
                        MaterialTheme(colorScheme = floatingColors) {
                            MiniPlayer(
                                current,
                                playback,
                                model.player,
                                clockEnabled = false,
                                onOpen = {},
                                onToggle = {},
                                onNext = {},
                            )
                        }
                    }) { close, dragHandle ->
                        Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalVisualsActive provides !queue) {
                                PlayerScreen(current, model, close, { queue = true }, { onLyricFile(current) }, dragHandle, lyricsPage)
                            }
                            GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
                        }
                    }
                }
                if (queue) {
                    PlaybackQueueSheet(
                        playback.queue,
                        playback.id,
                        current?.cover,
                        songFor = { id ->
                            model.findSong(id)?.let { song ->
                                if (song.isCloudSong()) song.copy(favorite = song.neteaseId in onlineLikes.ids) else song
                            }
                        },
                        onDismiss = { queue = false },
                        onPlay = { index -> model.act("player.jump") { model.player.jump(index) } },
                        onFavorite = model::favorite,
                        favoriteEnabled = { song ->
                            !song.isCloudSong() || (model.cloud.direct && onlineLikes.ready && song.neteaseId !in onlineLikes.pending)
                        },
                    )
                }
                remove?.let { song ->
                    GlassAlertDialog(
                        { remove = null },
                        title = { Text("从曲库移除？") },
                        text = { Text("《${song.title}》将从 AMI 曲库和播放队列中移除。原始音频会保留，重新扫描可再次导入。") },
                        confirmButton = {
                            TextButton({
                                model.remove(song)
                                remove = null
                            }) { Text("移除") }
                        },
                        dismissButton = { TextButton({ remove = null }) { Text("取消") } },
                    )
                }
            }
        }
    }
}

@Composable private fun MiniPlayer(
    song: Song,
    playback: PlaybackState,
    player: PlayerConnection,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    clockEnabled: Boolean = true,
    onBounds: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clock = rememberPlaybackClock(player, playback, clockEnabled)
    val duration = playback.duration.takeIf { it > 0 } ?: song.duration
    GlassSurface(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .testTag("mini-player")
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        prominent = true,
    ) {
        Column {
            Row(Modifier.padding(start = 9.dp, top = 7.dp, bottom = 7.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(song.cover, song.album, Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(12.dp))
                AnimatedContent(song.title to song.artist, Modifier.weight(1f), transitionSpec = { AmiMotion.page(true) }, label = "mini-track") { track ->
                    Column {
                        Text(track.first, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (playback.buffering) CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp)
                ActionIcon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "暂停" else "播放", onToggle, glass = false)
                ActionIcon(Icons.Rounded.SkipNext, "下一首", onNext, glass = false)
            }
            LinearProgressIndicator(progress = { if (duration > 0) (clock.value.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {})
        }
    }
}
