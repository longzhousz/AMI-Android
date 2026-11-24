package com.longzhousz.ami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
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
    refreshRequest: Int = 0,
    onAlbum: (Album) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onReorder: (List<String>) -> Unit,
    favorites: Boolean = false,
    showSourceSwitch: Boolean = true,
    active: Boolean = true,
) {
    var visit by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val albumGrid = rememberLazyGridState()
    val visibleAlbums by remember {
        derivedStateOf {
            albumGrid.layoutInfo.visibleItemsInfo
                .map { it.key }
                .toSet()
        }
    }
    // Keep the shelf's measured grid and search results available for the cover's return.
    val initial = remember(library, favorites) { library.search("", favorites, false) }
    val searched by produceState(initial, library, query, favorites) {
        value = if (query.isBlank()) initial else withContext(Dispatchers.Default) { library.search(query, favorites, false) }
    }
    val results = if (query.isBlank()) initial else searched
    val pages = rememberSaveableStateHolder()
    GlassBackdropHost(Modifier.fillMaxSize(), background = {
        if (selectedAlbum != null) PlayerBackdrop(selectedAlbum.cover) else GlassCanvas()
    }) {
        LibraryCollectionTransition(selectedAlbum, selectedAlbum?.key, visit) { album, _, currentScene, visibility ->
            val sceneActive = active && currentScene
            val sceneModifier = if (sceneActive) Modifier else Modifier.clearAndSetSemantics {}
            if (album == null) {
                pages.SaveableStateProvider("shelf") {
                    LocalLibraryHome(
                        results,
                        query,
                        onQuery = { query = it },
                        albumGrid,
                        loading,
                        refreshing,
                        currentId,
                        bottomInset,
                        onCloud = { if (sceneActive) onCloud() },
                        onRefresh = { if (sceneActive) onRefresh() },
                        refreshRequest = refreshRequest,
                        onAlbum = {
                            if (sceneActive) {
                                visit++
                                onAlbum(it)
                            }
                        },
                        onPlay = { songs, index -> if (sceneActive) onPlay(songs, index) },
                        onFavorite = { if (sceneActive) onFavorite(it) },
                        onRemove = { if (sceneActive) onRemove(it) },
                        coverModifier = { if (it.key in visibleAlbums) libraryCoverTransition(it.key, visibility) else Modifier },
                        modifier = sceneModifier,
                        active = sceneActive,
                        favorites = favorites,
                        showSourceSwitch = showSourceSwitch,
                    )
                }
            } else {
                LocalAlbumDetail(
                    album,
                    currentId,
                    onBack,
                    onPlay,
                    onFavorite,
                    onRemove,
                    onReorder,
                    bottomInset,
                    modifier = sceneModifier,
                    coverModifier = libraryCoverTransition(album.key, visibility),
                    active = sceneActive,
                )
            }
        }
    }
}

@Composable
internal fun LocalAlbumDetail(
    album: Album,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onReorder: (List<String>) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    active: Boolean = true,
) {
    var reorder by remember { mutableStateOf(false) }
    LibraryCollectionDetail(
        album.title,
        album.cover,
        album.artist,
        album.songs.size,
        remember(album.songs) { album.duration },
        listState = remember(album.key) { LazyListState() },
        bottomInset,
        onBack,
        modifier = modifier,
        coverModifier = coverModifier,
        trackListModifier = Modifier.testTag("local-album-tracks"),
        active = active,
        actions = {
            TextButton({ reorder = true }, enabled = active) {
                Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp))
                Text("曲序")
            }
        },
    ) {
        itemsIndexed(album.songs, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song,
                currentId == song.id,
                { if (active) onPlay(album.songs, index) },
                { if (active) onFavorite(song) },
                { if (active) onRemove(song) },
                modifier = Modifier.animateItem(),
                enabled = active,
            )
        }
    }
    if (reorder && active) {
        ReorderDialog(album, { reorder = false }, { ids ->
            onReorder(ids)
            reorder = false
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalLibraryHome(
    results: LibrarySearchResult,
    query: String,
    onQuery: (String) -> Unit,
    albumGrid: LazyGridState,
    loading: Boolean,
    refreshing: Boolean,
    currentId: String?,
    bottomInset: Dp,
    onCloud: () -> Unit,
    onRefresh: () -> Unit,
    refreshRequest: Int,
    onAlbum: (Album) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    coverModifier: @Composable (Album) -> Modifier,
    modifier: Modifier,
    active: Boolean,
    favorites: Boolean,
    showSourceSwitch: Boolean,
) {
    var mode by rememberSaveable { mutableIntStateOf(if (favorites) 1 else 0) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var requestSearchFocus by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    var headerHeight by rememberSaveable(density.density, density.fontScale, showSourceSwitch) { mutableIntStateOf(0) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val contentTop = if (headerHeight > 0) with(density) { headerHeight.toDp() } else statusTop + 128.dp
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val songList = rememberLazyListState()
    var previousQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != previousQuery) {
            previousQuery = query
            songList.requestScrollToItem(0)
            albumGrid.requestScrollToItem(0)
        }
    }
    LibraryNavigationRefresh(refreshRequest, albumGrid, songList, active) {
        onQuery("")
        searchExpanded = false
        focus.clearFocus()
        onRefresh()
    }
    LaunchedEffect(active, requestSearchFocus) {
        if (active && requestSearchFocus) searchFocus.requestFocus()
        requestSearchFocus = false
    }
    BackHandler(active && searchExpanded) {
        searchExpanded = false
        onQuery("")
        focus.clearFocus()
    }
    KeepLibraryAtTop(results.songs.firstOrNull()?.id, { songList.firstVisibleItemIndex == 0 && songList.firstVisibleItemScrollOffset == 0 }) { songList.requestScrollToItem(0) }
    val pullState = rememberPullToRefreshState()
    var songMenuOpen by remember { mutableStateOf(false) }
    val searchVisibility = updateTransition(searchExpanded, label = "local-library-search")
    val toolbarScroll =
        rememberLibraryScrollToolbar(
            scroll =
                when {
                    !active || loading -> null
                    mode == 0 -> albumGrid.takeIf { results.albums.isNotEmpty() }
                    else -> songList.takeIf { results.songs.isNotEmpty() }
                },
            refreshRequest,
            pullState,
        ) { searchVisibility.hasVisibleContent || refreshing || songMenuOpen }
    val toolbarVisibility = updateTransition(active && toolbarScroll.visible, label = "local-library-toolbar")
    val shelfBackdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize().testTag("local-library")) {
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
            modifier = if (toolbarVisibility.hasVisibleContent) Modifier.fillMaxSize().layerBackdrop(shelfBackdrop) else Modifier.fillMaxSize(),
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
                coverModifier = coverModifier,
                toolbarScroll = toolbarScroll,
                onSongMenuVisibilityChange = { songMenuOpen = it },
            )
        }
        LibraryFloatingToolbar(toolbarVisibility, Modifier.align(Alignment.TopCenter).testTag("local-library-toolbar")) {
            CompositionLocalProvider(LocalGlassBackdrop provides shelfBackdrop) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { headerHeight = it.height }
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    LocalLibraryHeaderRow(
                        searchExpanded,
                        showSourceSwitch,
                        onCloud,
                        onSearchToggle = {
                            searchExpanded = !searchExpanded
                            if (searchExpanded) {
                                requestSearchFocus = true
                            } else {
                                onQuery("")
                                focus.clearFocus()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    LocalLibraryModeRow(mode, favorites) { mode = it }
                    searchVisibility.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        LocalLibrarySearch(query, searchFocus, onQuery, { focus.clearFocus() })
                    }
                }
            }
        }
    }
}

@Composable private fun LocalLibraryHeaderRow(
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
    onMode: (Int) -> Unit,
) {
    if (favorites) {
        Text("本地喜欢", style = MaterialTheme.typography.labelMedium, color = FavoriteRed)
    } else {
        LibrarySegments(listOf("专辑", "歌曲"), mode, listOf("library-mode-albums", "library-mode-songs"), onMode)
    }
}

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
    toolbarScroll: LibraryScrollToolbarState,
    onSongMenuVisibilityChange: (Boolean) -> Unit,
) {
    val albumsTransition = remember { AmiMotion.page(false) }
    val songsTransition = remember { AmiMotion.page(true) }
    SideEffect {
        albumsTransition.targetContentZIndex = if (mode == 0) 1f else 0f
        songsTransition.targetContentZIndex = if (mode == 1) 1f else 0f
    }
    AnimatedContent(mode, Modifier.fillMaxSize(), transitionSpec = { if (targetState == 0) albumsTransition else songsTransition }, label = "local-library-layout") { layout ->
        val active = layout == mode
        val sceneModifier = if (active) Modifier.observeLibraryScroll(toolbarScroll) else Modifier.clearAndSetSemantics {}
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
                LibraryShelfGrid(
                    state = albumGrid,
                    firstKey = results.albums.first().key,
                    contentTop = contentTop,
                    bottomInset = bottomInset,
                    modifier = sceneModifier.testTag("local-album-grid"),
                ) {
                    items(results.albums, key = { it.key }) { album ->
                        LibraryShelfCard(
                            album.title,
                            album.artist.ifBlank { "专辑" },
                            album.cover,
                            enabled = active,
                            onClick = { onAlbum(album) },
                            modifier = Modifier.testTag("local-album:${album.key}"),
                            coverModifier = coverModifier(album),
                        )
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
                            enabled = active,
                            onMenuVisibilityChange = onSongMenuVisibilityChange,
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
