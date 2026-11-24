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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.NeteaseLoginActivity
import com.longzhousz.ami.R
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
    active: Boolean = true,
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
        searchActive = active,
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
    searchActive: Boolean = true,
) {
    val configured by cloud.configured.collectAsStateWithLifecycle()
    val likes by cloud.likes.collectAsStateWithLifecycle()
    val likesRevision by cloud.likesRevision.collectAsStateWithLifecycle()
    val session = likes.session
    val libraryState = if (state.session == null || state.session == session) state else CloudLibraryState()
    var seenLikesRevision by remember(session) { mutableLongStateOf(likesRevision) }
    // Scope the saved values to the login even if the tab was disposed during the switch.
    var query by key(cloud, session) { rememberSaveable { mutableStateOf("") } }
    var submitted by key(cloud, session) { rememberSaveable { mutableStateOf("") } }
    var searchExpanded by key(cloud, session) { rememberSaveable { mutableStateOf(false) } }
    var discoveryMenu by remember(session) { mutableStateOf(false) }
    var selectedKey by key(cloud, session) { rememberSaveable { mutableStateOf<String?>(null) } }
    var selectedVisit by remember(session) { mutableIntStateOf(0) }
    val selected = libraryState.library.collections.firstOrNull { it.key == selectedKey }
    val collection = if (favorites) libraryState.library.liked else selected
    val personal = remember(cloud, session, favorites) { PersonalLibrarySearch() }
    LaunchedEffect(searchActive, configured) { if (!searchActive || !configured) personal.close() }
    val personalHome = configured && searchActive && personal.expanded && selected == null
    val personalTerm = if (configured && searchActive && personal.expanded) personal.term else ""
    val personalResults = rememberPersonalLibraryMatches(cloud, libraryState.library, favorites, personalTerm, likes, likesRevision, personal.retry)
    val personalList = remember(session, personalTerm) { LazyListState() }
    val personalGrid = remember(session, personalTerm) { LazyGridState() }
    var personalPlaybackUnavailable by remember(session, personalTerm) { mutableStateOf(false) }
    val searching = submitted.isNotBlank()
    val showingTracks = favorites || selected != null || searching
    val key = if (searching) "search:$submitted" else collection?.key
    val initialPage = remember(session) { key?.let { cloud.savedPage(favorites, it) } }
    var offset by remember(session) { mutableIntStateOf(0) }
    var reload by remember(session) { mutableIntStateOf(0) }
    var loadedKey by remember(session) { mutableStateOf(key.takeIf { initialPage != null }) }
    var tracks by remember(session) { mutableStateOf(initialPage?.tracks.orEmpty()) }
    var albumSummary by remember(session) { mutableStateOf(initialPage?.albumSummary) }
    var next by remember(session) { mutableIntStateOf(initialPage?.nextOffset ?: 0) }
    var more by remember(session) { mutableStateOf(initialPage?.more ?: false) }
    var loading by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val personalFocus = remember { FocusRequester() }
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
    val visibleSearchCards by remember(personalGrid) {
        derivedStateOf {
            personalGrid.layoutInfo.visibleItemsInfo
                .map { it.key }
                .toSet()
        }
    }

    fun closeDetail() {
        selectedKey = null
        offset = 0
        reload = 0
        error = null
        focus.clearFocus()
    }

    fun closeSearch() {
        submitted = ""
        query = ""
        searchExpanded = false
        personal.close()
        if (!favorites) {
            offset = 0
            reload = 0
            error = null
        }
        focus.clearFocus()
    }

    fun select(card: CloudPlaylist) {
        if (cloud.likes.value.session != session || !cloud.configured.value) return
        selectedVisit++
        selectedKey = card.key
        offset = 0
        reload = 0
        error = null
        searchExpanded = false
        focus.clearFocus()
    }

    fun refresh() {
        if (loading || (!showingTracks && libraryState.refreshing)) return
        onRefreshLikes()
        if (showingTracks) {
            cloud.refreshCatalog()
            offset = 0
            reload++
        }
        if (!showingTracks || (favorites && collection == null)) onRefresh()
    }
    LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
    LaunchedEffect(personalHome, personal.requestFocus) {
        if (personalHome && personal.requestFocus) {
            personalFocus.requestFocus()
            personal.focusRequested()
        }
    }
    LibraryNavigationRefresh(refreshRequest, shelf, list.takeIf { favorites }) {
        closeSearch()
        if (!favorites) closeDetail()
        if (favorites) refresh() else onRefresh()
    }
    BackHandler(searchActive && (selected != null || searching || searchExpanded || personal.expanded)) {
        if (selected != null) closeDetail() else closeSearch()
    }
    LaunchedEffect(configured, session, key, offset, reload, if (favorites) likesRevision else 0L) {
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
        val pageRevision = likesRevision
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
            return currentKey == key && selectedVisit == pageVisit && cloud.configured.value && cloud.likes.value.session == session &&
                (!favorites || cloud.likesRevision.value == pageRevision)
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
            cloud.savePage(favorites, key, page.copy(tracks = merged, more = more), session, pageRevision)
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
            showingTracks && (!favorites || !personal.matching) && loadedKey == key && tracks.isNotEmpty() && more && !loading && next > offset &&
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
            val shelfBackdrop = rememberGlassLayer(popupBackground = LocalGlassBackdrop.current as? GlassLayer)
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val floatingToolbar = !favorites && selected == null && !searching
            val normalToolbar = !personalHome && (floatingToolbar || (favorites && (configured || sourceSelector != null)))
            val toolbar: @Composable (Boolean, Boolean) -> Unit = { toolbarSearching, personalToolbar ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = if (toolbarSearching) 8.dp else 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                        .testTag("collection-toolbar"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (toolbarSearching) {
                        ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回曲库", ::closeSearch)
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
                    if (!favorites && !toolbarSearching && !personalToolbar && !searchExpanded && configured) {
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
                                    DropdownMenuItem({ Text(stringResource(R.string.online_global_search)) }, {
                                        discoveryMenu = false
                                        searchExpanded = true
                                    }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
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
                    if (configured) {
                        val closing = personalToolbar || searchExpanded
                        ActionIcon(
                            if (closing) Icons.Rounded.Close else Icons.Rounded.Search,
                            stringResource(
                                when {
                                    closing -> R.string.library_search_collapse
                                    favorites -> R.string.online_favorites_search
                                    else -> R.string.online_library_search
                                },
                            ),
                            {
                                if (closing) closeSearch() else personal.open()
                            },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize().testTag(if (favorites) "online-favorites" else "online-library")) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(if (normalToolbar) shelfBackdrop.capture(Modifier) else Modifier)
                        .padding(top = if (floatingToolbar && searchExpanded) statusTop + 76.dp else 0.dp),
                ) {
                    if (!favorites && searching) toolbar(true, false)
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
                            OnlineLibrarySearchField(
                                query,
                                {
                                    query = it
                                    if (it.isEmpty()) {
                                        submitted = ""
                                        offset = 0
                                        error = null
                                    }
                                },
                                searchFocus,
                                stringResource(R.string.online_global_search_hint),
                                "online-search",
                            ) {
                                focus.clearFocus()
                                submitted = query.trim()
                                selectedKey = null
                                offset = 0
                                reload++
                            }
                        }
                        val libraryFailure = if (favorites) libraryState.playlistsError else libraryState.playlistsError ?: libraryState.albumsError
                        val failure = error ?: libraryFailure
                        val failureRow: @Composable (Modifier) -> Unit = { modifier ->
                            if (failure != null) {
                                OnlineLibraryNotice(failure, modifier) {
                                    if (error != null) {
                                        TextButton({ reload++ }) { Text("重试") }
                                    } else {
                                        if (libraryState.playlistsError != null) {
                                            TextButton(onRetryPlaylists) { Text("重试歌单") }
                                        }
                                        if (libraryState.albumsError != null && !favorites) {
                                            TextButton(onRetryAlbums) { Text("重试专辑") }
                                        }
                                    }
                                }
                            }
                        }
                        if (!personalHome && failure != null) {
                            failureRow(
                                Modifier
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
                                    ),
                            )
                        }
                        val pullState = rememberPullToRefreshState()
                        val personalMatching = personalHome && personal.matching
                        val refreshing = !personalMatching && (libraryState.refreshing || (loading && offset == 0))
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = { if (!personalMatching) refresh() },
                            state = pullState,
                            indicator = {
                                if (!personalHome && (favorites || !showingTracks)) {
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
                                    if (!personalMatching) {
                                        customActions =
                                            listOf(
                                                CustomAccessibilityAction(if (favorites) "刷新我喜欢" else "刷新曲库") {
                                                    refresh()
                                                    true
                                                },
                                            )
                                    }
                                },
                        ) {
                            LibraryCollectionTransition(selected, selected?.key, selectedVisit) { destination, visit, active, visibility ->
                                val sceneModifier = if (active) Modifier else Modifier.clearAndSetSemantics {}
                                val showPersonal = destination == null && searchActive && personal.expanded && !searching
                                val showMatches = showPersonal && personal.matching
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
                                val unavailableText = stringResource(R.string.online_track_unavailable)
                                Column(sceneModifier.fillMaxSize()) {
                                    if (showPersonal) {
                                        // In-flow controls sample the root background; only the floating toolbar samples the shelf.
                                        toolbar(false, true)
                                        OnlineLibrarySearchField(
                                            personal.query,
                                            {
                                                personalPlaybackUnavailable = false
                                                personal.changeQuery(it)
                                            },
                                            personalFocus,
                                            stringResource(if (favorites) R.string.online_favorites_search_hint else R.string.online_library_search_hint),
                                            "personal-library-search",
                                        ) { focus.clearFocus() }
                                        if (showMatches && !favorites) {
                                            Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                                                LibrarySegments(
                                                    listOf(stringResource(R.string.online_search_songs), stringResource(R.string.online_search_collections)),
                                                    personal.mode,
                                                    listOf("personal-search-songs", "personal-search-collections"),
                                                    personal::selectMode,
                                                )
                                            }
                                        }
                                        if (!showMatches || error == null) failureRow(Modifier)
                                    }

                                    if (showMatches) {
                                        val waitingForLibrary = if (favorites) libraryState.playlistsLoading else libraryState.refreshing
                                        PersonalSearchStatus(personalResults, waitingForLibrary, personal::retryFailed)
                                        if (personalPlaybackUnavailable) OnlineLibraryNotice(unavailableText)
                                        val empty =
                                            if (!personalResults.loading && !waitingForLibrary && personalResults.failedOffsets.isEmpty() && libraryFailure == null) {
                                                stringResource(R.string.online_personal_search_empty)
                                            } else {
                                                null
                                            }
                                        if (!favorites && personal.mode == 1) {
                                            OnlineCollectionShelf(
                                                personalResults.collections,
                                                personalGrid,
                                                contentTop = 4.dp,
                                                bottomInset = bottomInset,
                                                active = active,
                                                onSelect = ::select,
                                                modifier = Modifier.testTag("online-search-shelf"),
                                                empty = empty,
                                            ) { card ->
                                                if (card.key in visibleSearchCards) libraryCoverTransition(card.key, visibility) else Modifier
                                            }
                                        } else {
                                            LazyColumn(
                                                state = personalList,
                                                modifier = Modifier.fillMaxSize().testTag("online-tracks"),
                                                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottomInset),
                                            ) {
                                                onlineSongRows(
                                                    personalResults.tracks,
                                                    currentId,
                                                    likes,
                                                    cloud.direct,
                                                    favorites,
                                                    active,
                                                    onPlay = { rows, index ->
                                                        val currentLikes = cloud.likes.value
                                                        val currentFavorites = !favorites || (currentLikes == likes && cloud.likesRevision.value == likesRevision)
                                                        if (currentLikes.session == session && personal.term == personalTerm && currentFavorites) onPlay(rows, index)
                                                    },
                                                    onFavorite = { track -> if (cloud.likes.value.session == session) onFavorite(track) },
                                                    onUnavailable = { personalPlaybackUnavailable = true },
                                                )
                                                if (personalResults.tracks.isEmpty() && empty != null) item { ShelfEmpty(empty) }
                                            }
                                        }
                                    } else if (!favorites && destination == null && !searching) {
                                        val cards = libraryState.library.collections
                                        OnlineCollectionShelf(
                                            cards,
                                            shelf,
                                            contentTop =
                                                when {
                                                    showPersonal || searchExpanded -> 4.dp

                                                    failure != null -> 8.dp

                                                    // The initial gap scrolls away with the covers.
                                                    else -> statusTop + if (sourceSelector != null) 76.dp else 12.dp
                                                },
                                            bottomInset = bottomInset,
                                            active = active,
                                            onSelect = ::select,
                                            modifier = Modifier.testTag("online-shelf"),
                                            empty = "收藏的专辑和你创建的歌单，会出现在这里".takeIf { libraryState.loaded && failure == null },
                                        ) { card ->
                                            if (card.key in visibleCards) libraryCoverTransition(card.key, visibility) else Modifier
                                        }
                                    } else {
                                        val trackRows: LazyListScope.() -> Unit = {
                                            if (favorites) {
                                                item(key = "favorites-album-wall") {
                                                    FavoritesHero(pageTracks.take(30))
                                                }
                                            }
                                            onlineSongRows(
                                                pageTracks,
                                                currentId,
                                                likes,
                                                cloud.direct,
                                                favorites,
                                                active,
                                                onPlay = onPlay,
                                                onFavorite = onFavorite,
                                                onUnavailable = { error = unavailableText },
                                                insetFavorites = true,
                                            )
                                            if (more && loadedKey == key) {
                                                item { Spacer(Modifier.fillMaxWidth().height(20.dp)) }
                                            }
                                            if (pageTracks.isEmpty() && !loading && !libraryState.refreshing && libraryState.loaded && failure == null) {
                                                item { ShelfEmpty(if (favorites) "还没有红心歌曲" else "没有找到歌曲") }
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
                                                onBack = ::closeDetail,
                                                coverModifier = libraryCoverTransition(destination.key, visibility),
                                                trackListModifier = tracksModifier,
                                                active = active,
                                                padStatusBar = failure == null,
                                                tracks = trackRows,
                                            )
                                        } else {
                                            LazyColumn(
                                                state = pageList,
                                                modifier = Modifier.fillMaxSize().then(tracksModifier),
                                                contentPadding = PaddingValues(start = if (favorites) 0.dp else 24.dp, end = if (favorites) 0.dp else 24.dp, bottom = bottomInset),
                                                content = trackRows,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (normalToolbar) {
                    Box(Modifier.align(Alignment.TopEnd)) {
                        CompositionLocalProvider(LocalGlassBackdrop provides shelfBackdrop) { toolbar(false, false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineCollectionShelf(
    cards: List<CloudPlaylist>,
    grid: LazyGridState,
    contentTop: Dp,
    bottomInset: Dp,
    active: Boolean,
    onSelect: (CloudPlaylist) -> Unit,
    modifier: Modifier,
    empty: String?,
    coverModifier: @Composable (CloudPlaylist) -> Modifier,
) {
    LibraryShelfGrid(grid, cards.firstOrNull()?.key, contentTop, bottomInset, modifier) {
        items(cards, key = { it.key }) { card ->
            val subtitle =
                if (card.kind == "album") {
                    card.artist.ifBlank { stringResource(R.string.online_collection_album) }
                } else {
                    stringResource(R.string.online_collection_playlist_size, card.count)
                }
            LibraryShelfCard(
                card.title,
                subtitle,
                card.cover,
                enabled = active,
                onClick = { onSelect(card) },
                modifier = Modifier.testTag("collection-${card.key}"),
                coverModifier = coverModifier(card),
            )
        }
        if (cards.isEmpty() && empty != null) item(span = { GridItemSpan(maxLineSpan) }) { ShelfEmpty(empty) }
    }
}

private fun LazyListScope.onlineSongRows(
    tracks: List<CloudTrack>,
    currentId: String?,
    likes: CloudLikes,
    direct: Boolean,
    favorites: Boolean,
    active: Boolean,
    onPlay: (List<CloudTrack>, Int) -> Unit,
    onFavorite: (CloudTrack) -> Unit,
    onUnavailable: () -> Unit,
    insetFavorites: Boolean = false,
) {
    itemsIndexed(tracks, key = { _, it -> it.id }) { index, track ->
        SongRow(
            track.song().copy(favorite = track.originalId in likes.ids || (favorites && !likes.ready)),
            currentId == "netease:${track.id}",
            {
                if (active) {
                    if (track.available) {
                        onPlay(tracks, index)
                    } else {
                        onUnavailable()
                    }
                }
            },
            { if (active) onFavorite(track) },
            modifier =
                Modifier
                    .animateItem()
                    .then(if (favorites && insetFavorites) Modifier.padding(horizontal = 24.dp) else Modifier)
                    .alpha(if (track.available) 1f else .5f),
            favoriteEnabled = direct && likes.ready && track.originalId !in likes.pending,
            showFavorite = !favorites,
            enabled = active,
        )
    }
}

@Composable private fun ShelfEmpty(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
