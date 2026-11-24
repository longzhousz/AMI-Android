package com.longzhousz.ami.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.NeteaseLoginActivity
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.CloudMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable internal fun rememberNeteaseLogin(model: AmiViewModel): () -> Unit {
    val context = LocalContext.current
    val login =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                model.cancelCloudPreparation()
                if (model.findSong(model.player.state.value.id)?.isCloudSong() == true) model.player.pause()
                model.resetOnlineLibrary()
                model.refreshOnlineLibrary()
            }
        }
    return { login.launch(Intent(context, NeteaseLoginActivity::class.java)) }
}

@Composable internal fun OnlineLibraryScreen(
    model: AmiViewModel,
    favorites: Boolean,
    bottomInset: Dp = 24.dp,
    onImport: (() -> Unit)? = null,
    sourceSelector: (@Composable () -> Unit)? = null,
    refreshRequest: Int = 0,
    onOpenPlayer: () -> Unit = {},
) {
    val configured by model.cloud.configured.collectAsStateWithLifecycle()
    val state by model.onlineLibrary.collectAsStateWithLifecycle()
    val playback by model.player.state.collectAsStateWithLifecycle()
    val signIn = rememberNeteaseLogin(model)
    val discovering by model.discovering.collectAsStateWithLifecycle()
    val likes by model.cloud.likes.collectAsStateWithLifecycle()
    LaunchedEffect(configured) {
        if (!configured) {
            model.resetOnlineLibrary()
        } else if (!state.loaded) {
            model.refreshOnlineLibrary()
        }
    }
    LaunchedEffect(configured, likes.session) { if (configured) model.refreshCloudLikes() }
    OnlineLibraryContent(
        model.cloud,
        state,
        favorites,
        playback.id,
        model::refreshOnlineLibrary,
        model::playCloud,
        signIn,
        refreshRequest,
        bottomInset,
        onDiscover = { model.discover(it, onOpenPlayer) },
        discovering = discovering,
        onFavorite = model::favoriteCloud,
        onRefreshLikes = { model.refreshCloudLikes(force = true) },
        onImport = onImport,
        sourceSelector = sourceSelector,
        onRetryPlaylists = model::refreshOnlinePlaylists,
        onRetryAlbums = model::refreshOnlineAlbums,
    )
}

@Suppress("CyclomaticComplexMethod") // The existing online screen coordinates pagination, search and interrupted transitions.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun OnlineLibraryContent(
    cloud: CloudMusic,
    state: CloudLibraryState,
    favorites: Boolean,
    currentId: String?,
    onRefresh: () -> Unit,
    onPlay: (List<CloudTrack>, Int) -> Unit,
    onLogin: () -> Unit,
    refreshRequest: Int = 0,
    bottomInset: Dp = 24.dp,
    onDiscover: (String) -> Unit = {},
    discovering: Boolean = false,
    onFavorite: (CloudTrack) -> Unit = {},
    onRefreshLikes: () -> Unit = {},
    onImport: (() -> Unit)? = null,
    sourceSelector: (@Composable () -> Unit)? = null,
    onRetryPlaylists: () -> Unit = onRefresh,
    onRetryAlbums: () -> Unit = onRefresh,
) {
    val configured by cloud.configured.collectAsStateWithLifecycle()
    val likes by cloud.likes.collectAsStateWithLifecycle()
    val likesRevision by cloud.likesRevision.collectAsStateWithLifecycle()
    var seenLikesRevision by remember { mutableLongStateOf(likesRevision) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var discoveryMenu by remember { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedVisit by remember { mutableIntStateOf(0) }
    val selected = state.library.collections.firstOrNull { it.key == selectedKey }
    val collection = if (favorites) state.library.liked else selected
    val searching = submitted.isNotBlank()
    val showingTracks = favorites || selected != null || searching
    val key = if (searching) "search:$submitted" else collection?.key
    val initialPage = remember { key?.let { cloud.savedPage(favorites, it) } }
    var offset by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var loadedKey by remember { mutableStateOf(key.takeIf { initialPage != null }) }
    var tracks by remember { mutableStateOf(initialPage?.tracks.orEmpty()) }
    var albumSummary by remember { mutableStateOf(initialPage?.albumSummary) }
    var next by remember { mutableIntStateOf(initialPage?.nextOffset ?: 0) }
    var more by remember { mutableStateOf(initialPage?.more ?: false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val shelf = rememberLazyGridState()
    // Details intentionally do not restore scrolling when the same album is opened again.
    val list =
        if (!favorites && selected != null && !searching) {
            remember(key, selectedVisit) { LazyListState() }
        } else {
            key(key) { rememberLazyListState() }
        }
    val visibleCards by remember {
        derivedStateOf {
            shelf.layoutInfo.visibleItemsInfo
                .map { it.key }
                .toSet()
        }
    }

    fun back() {
        selectedKey = null
        submitted = ""
        query = ""
        offset = 0
        error = null
        searchExpanded = false
        focus.clearFocus()
    }

    fun refresh() {
        if (loading || (!showingTracks && state.refreshing)) return
        onRefreshLikes()
        if (showingTracks) {
            cloud.refreshCatalog()
            offset = 0
            reload++
        }
        if (!showingTracks || (favorites && collection == null)) onRefresh()
    }
    LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
    LibraryNavigationRefresh(refreshRequest, shelf, list.takeIf { favorites }) {
        back()
        if (favorites) refresh() else onRefresh()
    }
    BackHandler(selected != null || searching || searchExpanded) { back() }
    LaunchedEffect(configured, key, offset, reload, if (favorites) likesRevision else 0L) {
        if (!configured) {
            tracks = emptyList()
            albumSummary = null
            loadedKey = null
            offset = 0
            loading = false
            return@LaunchedEffect
        }
        if (key == null) {
            loading = false
            return@LaunchedEffect
        }
        if (favorites && seenLikesRevision != likesRevision) {
            seenLikesRevision = likesRevision
            // Reuse the locally patched prefix without a refresh spinner or scroll reset.
            // Restart pagination from its new nextOffset on the next near-end event.
            offset = 0
            reload = 0
            cloud.savedPage(true, key)?.let { saved ->
                tracks = saved.tracks
                albumSummary = saved.albumSummary
                loadedKey = key
                next = saved.nextOffset
                more = saved.more
                loading = false
                return@LaunchedEffect
            }
        }
        if (offset == 0 && reload == 0) {
            cloud.savedPage(favorites, key)?.let { saved ->
                tracks = saved.tracks
                albumSummary = saved.albumSummary
                loadedKey = key
                next = saved.nextOffset
                more = saved.more
                loading = false
                return@LaunchedEffect
            }
        }
        val pageOffset = offset
        val pageVisit = selectedVisit
        val pageSession = cloud.likes.value.session
        val previous = if (pageOffset == 0 || loadedKey != key) emptyList() else tracks

        fun isCurrentPage(): Boolean {
            val currentKey =
                if (submitted.isNotBlank()) {
                    "search:$submitted"
                } else if (favorites) {
                    collection?.key
                } else {
                    selectedKey
                }
            return currentKey == key && selectedVisit == pageVisit && cloud.configured.value && cloud.likes.value.session == pageSession
        }
        loading = true
        error = null
        if (loadedKey != key) {
            tracks = emptyList()
            albumSummary = null
            more = false
            list.scrollToItem(0)
        }
        try {
            val page = if (searching) cloud.search(submitted, pageOffset) else cloud.tracks(collection!!, pageOffset)
            val merged = withContext(Dispatchers.Default) { (previous + page.tracks).distinctBy { it.id }.take(20000) }
            if (!isCurrentPage()) return@LaunchedEffect
            tracks = merged
            albumSummary = page.albumSummary
            loadedKey = key
            next = page.nextOffset
            more = page.more
            if (tracks.size >= 20000) more = false
            cloud.savePage(favorites, key, page.copy(tracks = merged, more = more))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isCurrentPage()) error = failure.message?.take(150) ?: "音乐暂时无法载入"
        } finally {
            if (currentCoroutineContext().isActive && isCurrentPage()) loading = false
        }
    }
    LaunchedEffect(key, list) {
        snapshotFlow {
            // Wait until layout represents this page; a stale header-only layout looks near its end.
            val expectedItems =
                tracks.size + (if (selected != null) 1 else 0) +
                    (if (favorites) 1 else 0) + (if (more && loadedKey == key) 1 else 0)
            showingTracks && loadedKey == key && tracks.isNotEmpty() && more && !loading && next > offset &&
                list.layoutInfo.totalItemsCount == expectedItems && list.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                (
                    list.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                ) >= list.layoutInfo.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) offset = next }
    }
    val appColors = MaterialTheme.colorScheme
    val favoritesColors = if (appColors.isDark) FavoritesColors else appColors
    MaterialTheme(colorScheme = if (favorites) favoritesColors else appColors) {
        GlassBackdropHost(Modifier.fillMaxSize(), background = {
            if (selected != null) {
                PlayerBackdrop(selected.cover)
            } else if (favorites) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(favoritesColors.background) }
            } else {
                GlassCanvas()
            }
        }) {
            val shelfBackdrop = rememberLayerBackdrop()
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val floatingToolbar = !favorites && selected == null && !searching
            val toolbar: @Composable (Boolean) -> Unit = { toolbarSearching ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = if (toolbarSearching) 8.dp else 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
                        .testTag("collection-toolbar"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (toolbarSearching) {
                        ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回曲库", ::back)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "搜索结果",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        sourceSelector?.invoke()
                        Spacer(Modifier.weight(1f))
                    }
                    if (!favorites && !toolbarSearching && configured) {
                        // Match the shelf sampling and prominent optics of the local library's former menu action.
                        CompositionLocalProvider(LocalGlassBackdrop provides shelfBackdrop) {
                            Box {
                                ActionIcon(
                                    Icons.Rounded.Explore,
                                    "发现音乐",
                                    { discoveryMenu = true },
                                    enabled = !discovering,
                                    modifier = Modifier.liquidGlass(CircleShape, prominent = true),
                                )
                                GlassDropdownMenu(discoveryMenu, { discoveryMenu = false }) {
                                    DropdownMenuItem({ Text("私人 FM") }, {
                                        discoveryMenu = false
                                        onDiscover("fm")
                                    }, leadingIcon = { Icon(Icons.Rounded.Radio, null) })
                                    DropdownMenuItem({ Text("每日推荐") }, {
                                        discoveryMenu = false
                                        onDiscover("daily")
                                    }, leadingIcon = { Icon(Icons.Rounded.WbSunny, null) })
                                }
                            }
                        }
                    }
                    if (!favorites && configured) {
                        ActionIcon(
                            if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                            if (searchExpanded) "收起搜索" else "搜索音乐",
                            {
                                if (searchExpanded) back() else searchExpanded = true
                            },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize().testTag(if (favorites) "online-favorites" else "online-library")) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(if (floatingToolbar || (favorites && sourceSelector != null)) Modifier.layerBackdrop(shelfBackdrop) else Modifier)
                        .padding(top = if (floatingToolbar && searchExpanded) statusTop + 76.dp else 0.dp),
                ) {
                    if (!favorites && searching) toolbar(true)
                    if (!configured) {
                        if (favorites) FavoritesHero(emptyList())
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(if (favorites) Icons.Rounded.FavoriteBorder else Icons.Rounded.LibraryMusic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(if (!favorites && onImport != null) "选择音乐来源" else "登录后，音乐就在这里", style = MaterialTheme.typography.titleMedium)
                                if (!favorites && onImport != null) {
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        GlassButton(onLogin, Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("登录网易云", textAlign = TextAlign.Center) }
                                        GlassButton(onImport, Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("导入本地音乐", textAlign = TextAlign.Center) }
                                    }
                                } else {
                                    GlassButton(onLogin) { Text("登录网易云") }
                                }
                            }
                        }
                    } else {
                        AnimatedVisibility(!favorites && selected == null && searchExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            OutlinedTextField(
                                query,
                                { query = it },
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 18.dp)
                                    .liquidGlass(RoundedCornerShape(22.dp))
                                    .focusRequester(searchFocus)
                                    .testTag("online-search"),
                                placeholder = { Text("搜索歌曲、艺人") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        ActionIcon(Icons.Rounded.Close, "清空搜索", {
                                            query = ""
                                            submitted = ""
                                            offset = 0
                                        })
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(22.dp),
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                    ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions =
                                    KeyboardActions(onSearch = {
                                        focus.clearFocus()
                                        submitted = query.trim()
                                        selectedKey = null
                                        offset = 0
                                        reload++
                                    }),
                            )
                        }
                        val libraryFailure = if (favorites) state.playlistsError else state.playlistsError ?: state.albumsError
                        val failure = error ?: libraryFailure
                        if (failure != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (favorites || (!searching && !searchExpanded)) Modifier.statusBarsPadding() else Modifier)
                                    .padding(
                                        top =
                                            if (floatingToolbar && !searchExpanded) {
                                                76.dp
                                            } else if (!favorites && !searching && !searchExpanded) {
                                                12.dp
                                            } else {
                                                0.dp
                                            },
                                        start = 24.dp,
                                        end = 24.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(failure, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (error == null) {
                                    if (state.playlistsError != null) {
                                        TextButton(onRetryPlaylists) { Text("重试歌单") }
                                    }
                                    if (state.albumsError != null && !favorites) {
                                        TextButton(onRetryAlbums) { Text("重试专辑") }
                                    }
                                    if (state.playlistsError == null && state.albumsError == null) {
                                        TextButton(onRefresh) { Text("重试") }
                                    }
                                } else {
                                    TextButton({ reload++ }) { Text("重试") }
                                }
                            }
                        }
                        val pullState = rememberPullToRefreshState()
                        val refreshing = state.refreshing || (loading && offset == 0)
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = ::refresh,
                            state = pullState,
                            indicator = {
                                if (favorites || !showingTracks) {
                                    PullToRefreshDefaults.Indicator(
                                        modifier =
                                            Modifier
                                                .align(Alignment.TopCenter)
                                                .then(if (!favorites && !searchExpanded && failure == null) Modifier.padding(top = statusTop + if (sourceSelector != null) 76.dp else 12.dp) else Modifier)
                                                .testTag("online-refresh-indicator"),
                                        isRefreshing = refreshing,
                                        state = pullState,
                                    )
                                }
                            },
                            modifier =
                                Modifier.weight(1f).semantics {
                                    customActions =
                                        listOf(
                                            CustomAccessibilityAction(if (favorites) "刷新我喜欢" else "刷新曲库") {
                                                refresh()
                                                true
                                            },
                                        )
                                },
                        ) {
                            LibraryCollectionTransition(selected, selected?.key, selectedVisit) { destination, visit, active, visibility ->
                                val sceneModifier = if (active) Modifier else Modifier.clearAndSetSemantics {}
                                // Exiting details retain their own list, including during rapid reversal.
                                val pageList = remember(destination?.key, visit, searching, submitted) { list }
                                val pageKey =
                                    if (searching) {
                                        "search:$submitted"
                                    } else if (favorites) {
                                        collection?.key
                                    } else {
                                        destination?.key
                                    }
                                val currentPage = loadedKey == pageKey
                                val cachedPage = pageKey?.takeUnless { currentPage }?.let { cloud.savedPage(favorites, it) }
                                val pageTracks = if (currentPage) tracks else cachedPage?.tracks.orEmpty()
                                if (!favorites && destination == null && !searching) {
                                    LibraryShelfGrid(
                                        state = shelf,
                                        firstKey =
                                            state.library.collections
                                                .firstOrNull()
                                                ?.key,
                                        contentTop =
                                            when {
                                                searchExpanded -> 4.dp

                                                // The failure row already sits below the status bar and toolbar.
                                                failure != null -> 8.dp

                                                // The initial gap scrolls away with the covers.
                                                else -> statusTop + if (sourceSelector != null) 76.dp else 12.dp
                                            },
                                        bottomInset = bottomInset,
                                        modifier = sceneModifier.testTag("online-shelf"),
                                    ) {
                                        items(state.library.collections, key = { it.key }) { card ->
                                            LibraryShelfCard(
                                                card.title,
                                                if (card.kind == "album") card.artist.ifBlank { "专辑" } else "歌单 · ${card.count} 首",
                                                card.cover,
                                                enabled = active,
                                                onClick = {
                                                    selectedVisit++
                                                    selectedKey = card.key
                                                    submitted = ""
                                                    offset = 0
                                                    reload = 0
                                                    error = null
                                                    searchExpanded = false
                                                    focus.clearFocus()
                                                },
                                                modifier = Modifier.testTag("collection-${card.key}"),
                                                coverModifier = if (card.key in visibleCards) libraryCoverTransition(card.key, visibility) else Modifier,
                                            )
                                        }
                                        if (state.loaded && state.library.collections.isEmpty() && failure == null) {
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                ShelfEmpty("收藏的专辑和你创建的歌单，会出现在这里")
                                            }
                                        }
                                    }
                                } else {
                                    val trackRows: LazyListScope.() -> Unit = {
                                        if (favorites) {
                                            item(key = "favorites-album-wall") {
                                                FavoritesHero(pageTracks.take(30))
                                            }
                                        }
                                        itemsIndexed(pageTracks, key = { _, it -> it.id }) { index, track ->
                                            SongRow(
                                                track.song().copy(favorite = track.originalId in likes.ids || (favorites && !likes.ready)),
                                                currentId == "netease:${track.id}",
                                                {
                                                    if (active) {
                                                        if (track.available) onPlay(pageTracks, index) else error = "当前账号暂时无法播放这首歌"
                                                    }
                                                },
                                                { if (active) onFavorite(track) },
                                                modifier =
                                                    Modifier
                                                        .animateItem()
                                                        .then(if (favorites) Modifier.padding(horizontal = 24.dp) else Modifier)
                                                        .alpha(if (track.available) 1f else .5f),
                                                favoriteEnabled = cloud.direct && likes.ready && track.originalId !in likes.pending,
                                                showFavorite = !favorites,
                                                enabled = active,
                                            )
                                        }
                                        if (more && loadedKey == key) {
                                            item {
                                                Spacer(Modifier.fillMaxWidth().height(20.dp))
                                            }
                                        }
                                        if (pageTracks.isEmpty() && !loading && !state.refreshing && state.loaded && failure == null) {
                                            item {
                                                ShelfEmpty(if (favorites) "还没有红心歌曲" else "没有找到歌曲")
                                            }
                                        }
                                    }
                                    val tracksModifier = Modifier.testTag("online-tracks")
                                    if (!favorites && destination != null) {
                                        val album = destination.kind == "album"
                                        val summary = if (currentPage) albumSummary else cachedPage?.albumSummary
                                        LibraryCollectionDetail(
                                            destination.title,
                                            destination.cover,
                                            destination.artist,
                                            summary?.count ?: destination.count.takeIf { it > 0 || !album },
                                            summary?.duration,
                                            listState = pageList,
                                            bottomInset,
                                            onBack = ::back,
                                            modifier = sceneModifier,
                                            coverModifier = libraryCoverTransition(destination.key, visibility),
                                            trackListModifier = tracksModifier,
                                            active = active,
                                            padStatusBar = failure == null,
                                            tracks = trackRows,
                                        )
                                    } else {
                                        LazyColumn(
                                            state = pageList,
                                            modifier = sceneModifier.fillMaxSize().then(tracksModifier),
                                            contentPadding = PaddingValues(start = if (favorites) 0.dp else 24.dp, end = if (favorites) 0.dp else 24.dp, bottom = bottomInset),
                                            content = trackRows,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (floatingToolbar || (favorites && sourceSelector != null)) {
                    Box(Modifier.align(Alignment.TopEnd)) {
                        CompositionLocalProvider(LocalGlassBackdrop provides shelfBackdrop) { toolbar(false) }
                    }
                }
            }
        }
    }
}

@Composable private fun ShelfEmpty(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
