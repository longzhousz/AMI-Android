package com.longzhousz.ami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.longzhousz.ami.core.Album
import com.longzhousz.ami.core.LibrarySearchResult
import com.longzhousz.ami.core.LibrarySnapshot
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun LibrarySourceSwitch(
    local: Boolean,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
) {
    LibrarySegments(listOf("本地", "网易云"), if (local) 0 else 1, listOf("library-source-local", "library-source-cloud")) {
        if (it == 0) onLocal() else onCloud()
    }
}

@Composable private fun LibrarySegments(
    labels: List<String>,
    selected: Int,
    tags: List<String>,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.liquidGlass(CircleShape, prominent = true).padding(horizontal = 4.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Box(
                Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .testTag(tags[index]),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class LocalCollectionScene(
    val album: Album?,
    val visit: Int,
) {
    val key get() = album?.let { "${it.key}:$visit" } ?: "shelf"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun LocalLibraryScreen(
    library: LibrarySnapshot,
    loading: Boolean,
    refreshing: Boolean,
    currentId: String?,
    bottomInset: Dp,
    selectedAlbum: Album?,
    onCloud: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshRequest: () -> Int,
    onAlbum: (Album) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onReorder: (List<String>) -> Unit,
    initialFavorites: Boolean = false,
    showSourceSwitch: Boolean = true,
) {
    var visit by rememberSaveable { mutableIntStateOf(0) }
    val target = LocalCollectionScene(selectedAlbum, visit)
    val pages = rememberSaveableStateHolder()
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shelfTransition = remember { fadeIn(tween(200, 80)) togetherWith fadeOut(tween(140)) }
        val detailTransition = remember { fadeIn(tween(200, 80)) togetherWith fadeOut(tween(140)) }
        SideEffect {
            shelfTransition.targetContentZIndex = if (target.album == null) 1f else 0f
            detailTransition.targetContentZIndex = if (target.album != null) 1f else 0f
        }
        AnimatedContent(
            target,
            Modifier.fillMaxSize(),
            contentKey = { it.key },
            transitionSpec = { if (targetState.album == null) shelfTransition else detailTransition },
            label = "local-collection-transition",
        ) { scene ->
            val active = scene.key == target.key
            val sceneModifier = if (active) Modifier else Modifier.clearAndSetSemantics {}
            val album = scene.album
            if (album == null) {
                pages.SaveableStateProvider("shelf") {
                    LocalLibraryHome(
                        library,
                        loading,
                        refreshing,
                        currentId,
                        bottomInset,
                        onCloud = { if (active) onCloud() },
                        onRefresh = { if (active) onRefresh() },
                        onRefreshRequest = onRefreshRequest,
                        onAlbum = {
                            if (active) {
                                visit++
                                onAlbum(it)
                            }
                        },
                        onPlay = { songs, index -> if (active) onPlay(songs, index) },
                        onFavorite = { if (active) onFavorite(it) },
                        onRemove = { if (active) onRemove(it) },
                        coverModifier = { card ->
                            Modifier.sharedElement(
                                rememberSharedContentState("local-cover:${card.key}"),
                                animatedVisibilityScope = this@AnimatedContent,
                                boundsTransform = { _, _ -> spring(dampingRatio = .9f, stiffness = 320f) },
                            )
                        },
                        modifier = sceneModifier,
                        active = active,
                        initialFavorites = initialFavorites,
                        showSourceSwitch = showSourceSwitch,
                    )
                }
            } else {
                AlbumScreen(
                    album,
                    currentId,
                    onBack = { if (active) onBack() },
                    onPlay = { songs, index -> if (active) onPlay(songs, index) },
                    onFavorite = { if (active) onFavorite(it) },
                    onRemove = { if (active) onRemove(it) },
                    onReorder = { if (active) onReorder(it) },
                    bottomInset = bottomInset,
                    modifier = sceneModifier,
                    coverModifier =
                        Modifier.sharedElement(
                            rememberSharedContentState("local-cover:${album.key}"),
                            animatedVisibilityScope = this@AnimatedContent,
                            boundsTransform = { _, _ -> spring(dampingRatio = .9f, stiffness = 320f) },
                        ),
                    active = active,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalLibraryHome(
    library: LibrarySnapshot,
    loading: Boolean,
    refreshing: Boolean,
    currentId: String?,
    bottomInset: Dp,
    onCloud: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshRequest: () -> Int,
    onAlbum: (Album) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    coverModifier: @Composable (Album) -> Modifier,
    modifier: Modifier,
    active: Boolean,
    initialFavorites: Boolean,
    showSourceSwitch: Boolean,
) {
    var mode by rememberSaveable { mutableIntStateOf(if (initialFavorites) 1 else 0) }
    var favorites by rememberSaveable { mutableStateOf(initialFavorites) }
    var recent by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var headerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val contentTop = if (headerHeight > 0) with(density) { headerHeight.toDp() } else statusTop + 128.dp
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val songList = rememberLazyListState()
    val albumGrid = rememberLazyGridState()
    val visibleAlbums by remember {
        derivedStateOf {
            albumGrid.layoutInfo.visibleItemsInfo
                .map { it.key }
                .toSet()
        }
    }
    var previousQuery by rememberSaveable { mutableStateOf(query) }
    var previousRecent by rememberSaveable { mutableStateOf(recent) }
    var previousFavorites by rememberSaveable { mutableStateOf(favorites) }
    LaunchedEffect(query, recent, favorites) {
        if (localFilterChanged(query, recent, favorites, previousQuery, previousRecent, previousFavorites)) {
            previousQuery = query
            previousRecent = recent
            previousFavorites = favorites
            songList.scrollToItem(0)
            albumGrid.scrollToItem(0)
        }
    }
    val refreshRequest = onRefreshRequest()
    var seenRefreshRequest by remember { mutableIntStateOf(refreshRequest) }
    LaunchedEffect(refreshRequest) {
        if (refreshRequest != seenRefreshRequest) {
            seenRefreshRequest = refreshRequest
            songList.scrollToItem(0)
            albumGrid.scrollToItem(0)
        }
    }
    LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
    BackHandler(active && (searchExpanded || (favorites && !initialFavorites))) {
        if (searchExpanded) {
            searchExpanded = false
            query = ""
            focus.clearFocus()
        } else {
            favorites = false
        }
    }
    val initial = remember(library, favorites) { library.search("", favorites, false) }
    val searched by produceState(initial, library, query, favorites, recent) {
        value = withContext(Dispatchers.Default) { library.search(query, favorites, recent) }
    }
    val results = if (query.isBlank() && !recent) initial else searched
    val shelfBackdrop = rememberLayerBackdrop()
    GlassBackdropHost(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().testTag("local-library")) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = contentTop),
                        isRefreshing = refreshing,
                        state = pullState,
                    )
                },
                modifier = Modifier.fillMaxSize().layerBackdrop(shelfBackdrop),
            ) {
                LocalLibraryItems(
                    mode,
                    loading,
                    results,
                    favorites,
                    query,
                    songList,
                    albumGrid,
                    currentId,
                    contentTop,
                    bottomInset,
                    {
                        focus.clearFocus()
                        onAlbum(it)
                    },
                    onPlay,
                    onFavorite,
                    onRemove,
                    coverModifier = { if (it.key in visibleAlbums) coverModifier(it) else Modifier },
                )
            }
            CompositionLocalProvider(LocalGlassBackdrop provides shelfBackdrop) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onSizeChanged { headerHeight = it.height }
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    LocalLibraryHeaderRow(
                        favorites,
                        searchExpanded,
                        showSourceSwitch,
                        onCloud,
                        onSearchToggle = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) {
                                query = ""
                                focus.clearFocus()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    LocalLibraryModeRow(mode, favorites, initialFavorites, { mode = it }, { favorites = false })
                    AnimatedVisibility(searchExpanded) {
                        LocalLibrarySearch(query, searchFocus, { query = it }, { focus.clearFocus() })
                    }
                }
            }
        }
    }
}

@Composable private fun LocalLibraryHeaderRow(
    favorites: Boolean,
    searchExpanded: Boolean,
    showSourceSwitch: Boolean,
    onCloud: () -> Unit,
    onSearchToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showSourceSwitch) LibrarySourceSwitch(true, {}, onCloud)
        Spacer(Modifier.weight(1f))
        ActionIcon(
            if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
            if (searchExpanded) "收起搜索" else "搜索本地音乐",
            onSearchToggle,
        )
    }
}

@Composable private fun LocalLibraryModeRow(
    mode: Int,
    favorites: Boolean,
    initialFavorites: Boolean,
    onMode: (Int) -> Unit,
    onAllSongs: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!initialFavorites) {
            LibrarySegments(listOf("专辑", "歌曲"), mode, listOf("library-mode-albums", "library-mode-songs")) {
                onMode(it)
                if (it == 0) onAllSongs()
            }
        }
        if (favorites) {
            Text(
                "本地喜欢",
                Modifier.padding(start = if (initialFavorites) 0.dp else 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = FavoriteRed,
            )
        }
    }
}

private fun localFilterChanged(
    query: String,
    recent: Boolean,
    favorites: Boolean,
    oldQuery: String,
    oldRecent: Boolean,
    oldFavorites: Boolean,
) = query != oldQuery || recent != oldRecent || favorites != oldFavorites

@Composable private fun LocalLibraryItems(
    mode: Int,
    loading: Boolean,
    results: LibrarySearchResult,
    favorites: Boolean,
    query: String,
    songList: LazyListState,
    albumGrid: LazyGridState,
    currentId: String?,
    contentTop: Dp,
    bottomInset: Dp,
    onAlbum: (Album) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    coverModifier: @Composable (Album) -> Modifier,
) {
    val albumsTransition = remember { AmiMotion.page(false) }
    val songsTransition = remember { AmiMotion.page(true) }
    SideEffect {
        albumsTransition.targetContentZIndex = if (mode == 0) 1f else 0f
        songsTransition.targetContentZIndex = if (mode == 1) 1f else 0f
    }
    AnimatedContent(mode, Modifier.fillMaxSize(), transitionSpec = { if (targetState == 0) albumsTransition else songsTransition }, label = "local-library-layout") { layout ->
        val active = layout == mode
        val sceneModifier = if (active) Modifier else Modifier.clearAndSetSemantics {}
        when {
            loading -> {
                Box(sceneModifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            (layout == 0 && results.albums.isEmpty()) || (layout == 1 && results.songs.isEmpty()) -> {
                Box(sceneModifier.fillMaxSize().padding(top = contentTop, bottom = bottomInset), contentAlignment = Alignment.Center) {
                    Text(if (favorites && query.isBlank()) "暂无本地喜欢" else "没有找到匹配的音乐", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            layout == 0 -> {
                LazyVerticalGrid(
                    GridCells.Adaptive(145.dp),
                    state = albumGrid,
                    modifier = sceneModifier.fillMaxSize().testTag("local-album-grid"),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = contentTop, bottom = bottomInset),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(results.albums, key = { it.key }) { album ->
                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 320f)).clickable(enabled = active) { onAlbum(album) }.testTag("local-album:${album.key}")) {
                            Cover(album.cover, album.title, Modifier.fillMaxWidth().aspectRatio(1f).then(coverModifier(album)))
                            Text(album.title, Modifier.padding(top = 10.dp, start = 2.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(album.artist.ifBlank { "专辑" }, Modifier.padding(start = 2.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = songList,
                    modifier = sceneModifier.fillMaxSize().testTag("library-song-list"),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = contentTop, bottom = bottomInset),
                ) {
                    itemsIndexed(results.songs, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            song,
                            song.id == currentId,
                            { if (active) onPlay(results.songs, index) },
                            { if (active) onFavorite(song) },
                            { if (active) onRemove(song) },
                            modifier = Modifier.animateItem(placementSpec = spring(stiffness = 320f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun LocalLibrarySearch(
    query: String,
    searchFocus: FocusRequester,
    onQuery: (String) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        query,
        onQuery,
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .liquidGlass(RoundedCornerShape(22.dp))
            .focusRequester(searchFocus)
            .testTag("library-search"),
        placeholder = { Text("搜索专辑、歌曲、艺人") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "清空搜索", { onQuery("") }) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        singleLine = true,
        shape = RoundedCornerShape(22.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
