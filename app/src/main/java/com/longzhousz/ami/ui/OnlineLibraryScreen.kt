package com.longzhousz.ami.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.NeteaseLoginActivity
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.CloudMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun rememberNeteaseLogin(model: AmiViewModel): () -> Unit {
    val context = LocalContext.current
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            model.cancelCloudPreparation()
            if (model.findSong(model.player.state.value.id)?.isCloudSong() == true) model.player.pause()
            model.resetOnlineLibrary(); model.refreshOnlineLibrary()
        }
    }
    return { login.launch(Intent(context, NeteaseLoginActivity::class.java)) }
}

@Composable internal fun OnlineLibraryScreen(model: AmiViewModel, favorites: Boolean, bottomInset: Dp = 24.dp, onOpenPlayer: () -> Unit = {}) {
    val configured by model.cloud.configured.collectAsStateWithLifecycle()
    val state by model.onlineLibrary.collectAsStateWithLifecycle()
    val playback by model.player.state.collectAsStateWithLifecycle()
    val signIn = rememberNeteaseLogin(model)
    val refresh by model.onlineRefresh.collectAsStateWithLifecycle()
    val discovering by model.discovering.collectAsStateWithLifecycle()
    val likes by model.cloud.likes.collectAsStateWithLifecycle()
    var request by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { if (model.consumeOnlineRefresh(if (favorites) 1 else 0)) request++ }
    LaunchedEffect(configured) {
        if (!configured) model.resetOnlineLibrary()
        else if (!state.loaded) model.refreshOnlineLibrary()
    }
    LaunchedEffect(configured, likes.session) { if (configured) model.refreshCloudLikes() }
    OnlineLibraryContent(model.cloud, state, favorites, playback.id, model::refreshOnlineLibrary, model::playCloud, signIn, request, bottomInset,
        onDiscover = { model.discover(it, onOpenPlayer) }, discovering = discovering,
        onFavorite = model::favoriteCloud, onRefreshLikes = { model.refreshCloudLikes(force = true) })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable internal fun OnlineLibraryContent(
    cloud: CloudMusic, state: CloudLibraryState, favorites: Boolean, currentId: String?,
    onRefresh: () -> Unit, onPlay: (List<CloudTrack>, Int) -> Unit, onLogin: () -> Unit,
    refreshRequest: Int = 0, bottomInset: Dp = 24.dp,
    onDiscover: (String) -> Unit = {}, discovering: Boolean = false,
    onFavorite: (CloudTrack) -> Unit = {}, onRefreshLikes: () -> Unit = {},
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
    var next by remember { mutableIntStateOf(initialPage?.nextOffset ?: 0) }
    var more by remember { mutableStateOf(initialPage?.more ?: false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val shelf = rememberLazyGridState()
    val list = key(key) { rememberLazyListState() }
    val visibleCards by remember { derivedStateOf { shelf.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
    val visibleTracks = tracks.takeIf { key == loadedKey }.orEmpty()
    var seenRevision by rememberSaveable { mutableLongStateOf(state.revision) }
    fun back() { selectedKey = null; submitted = ""; query = ""; offset = 0; error = null; searchExpanded = false; focus.clearFocus() }
    fun refresh() {
        if (loading || !showingTracks && state.refreshing) return
        onRefreshLikes()
        if (showingTracks) { cloud.refreshCatalog(); offset = 0; reload++ }
        if (!showingTracks || favorites && collection == null) onRefresh()
    }
    LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
    LaunchedEffect(refreshRequest) {
        if (refreshRequest > 0) {
            if (favorites) refresh() else { back(); onRefresh() }
        }
    }
    LaunchedEffect(state.revision) {
        if (seenRevision != state.revision) { seenRevision = state.revision; shelf.requestScrollToItem(0) }
    }
    BackHandler(selected != null || searching || searchExpanded) { back() }
    LaunchedEffect(configured, key, offset, reload, if (favorites) likesRevision else 0L) {
        if (!configured) { tracks = emptyList(); loadedKey = null; offset = 0; loading = false; return@LaunchedEffect }
        if (key == null) { loading = false; return@LaunchedEffect }
        if (favorites && seenLikesRevision != likesRevision) {
            seenLikesRevision = likesRevision
            // Reuse the locally patched prefix without a refresh spinner or scroll reset.
            // Restart pagination from its new nextOffset on the next near-end event.
            offset = 0; reload = 0
            cloud.savedPage(true, key)?.let { saved ->
                tracks = saved.tracks; loadedKey = key; next = saved.nextOffset; more = saved.more; loading = false
                return@LaunchedEffect
            }
        }
        if (offset == 0 && reload == 0) cloud.savedPage(favorites, key)?.let { saved ->
            tracks = saved.tracks; loadedKey = key; next = saved.nextOffset; more = saved.more; loading = false
            return@LaunchedEffect
        }
        loading = true; error = null
        if (loadedKey != key) { tracks = emptyList(); more = false; list.scrollToItem(0) }
        try {
            val page = if (searching) cloud.search(submitted, offset) else cloud.tracks(collection!!, offset)
            val previous = if (offset == 0 || loadedKey != key) emptyList() else tracks
            tracks = withContext(Dispatchers.Default) { (previous + page.tracks).distinctBy { it.id }.take(20000) }
            loadedKey = key; next = page.nextOffset; more = page.more
            if (tracks.size >= 20000) more = false
            cloud.savePage(favorites, key, CloudTracks(tracks, next, more))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message?.take(150) ?: "音乐暂时无法载入" }
        finally { if (currentCoroutineContext().isActive) loading = false }
    }
    LaunchedEffect(key, list) {
        snapshotFlow {
            // Wait until layout represents this page; a stale header-only layout looks near its end.
            val expectedItems = tracks.size + (if (selected != null) 1 else 0) +
                (if (favorites && tracks.isNotEmpty()) 1 else 0) + (if (more && loadedKey == key) 1 else 0)
            showingTracks && loadedKey == key && tracks.isNotEmpty() && more && !loading && next > offset &&
                list.layoutInfo.totalItemsCount == expectedItems && list.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) offset = next }
    }
    GlassBackdropHost(Modifier.fillMaxSize(), background = {
        if (selected != null) PlayerBackdrop(selected.cover) else GlassCanvas()
    }) {
    Column(Modifier.fillMaxSize().testTag(if (favorites) "online-favorites" else "online-library")) {
        Row(Modifier.fillMaxWidth().padding(start = if (selected != null || searching) 8.dp else 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selected != null || searching) ActionIcon(Icons.Rounded.ArrowBack, "返回曲库", ::back)
            Text(if (favorites) "我喜欢" else if (searching) "搜索结果" else selected?.title ?: "曲库",
                style = if (selected == null && !searching) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onDoubleClick = {
                    if (favorites) refresh() else if (selected == null && !searching) onRefresh()
                }))
            if (!favorites && selected == null && !searching && configured) Box {
                ActionIcon(Icons.Rounded.Explore, "发现音乐", { discoveryMenu = true }, enabled = !discovering)
                GlassDropdownMenu(discoveryMenu, { discoveryMenu = false }) {
                    DropdownMenuItem({ Text("私人 FM") }, { discoveryMenu = false; onDiscover("fm") }, leadingIcon = { Icon(Icons.Rounded.Radio, null) })
                    DropdownMenuItem({ Text("每日推荐") }, { discoveryMenu = false; onDiscover("daily") }, leadingIcon = { Icon(Icons.Rounded.WbSunny, null) })
                }
            }
            if (!favorites && selected == null && configured) ActionIcon(if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                if (searchExpanded) "收起搜索" else "搜索音乐", {
                    if (searchExpanded) back() else searchExpanded = true
                })
        }
        if (!configured) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (favorites) Icons.Rounded.FavoriteBorder else Icons.Rounded.LibraryMusic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("登录后，音乐就在这里", style = MaterialTheme.typography.titleMedium)
                    GlassButton(onLogin) { Text("登录网易云") }
                }
            }
        } else {
            AnimatedVisibility(!favorites && selected == null && searchExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                OutlinedTextField(query, { query = it },
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp).liquidGlass(RoundedCornerShape(22.dp)).focusRequester(searchFocus).testTag("online-search"),
                placeholder = { Text("搜索歌曲、艺人") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "清空搜索", { query = ""; submitted = ""; offset = 0 }) },
                singleLine = true, shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus(); submitted = query.trim(); selectedKey = null; offset = 0; reload++
                }))
            }
            val failure = error ?: state.error
            if (failure != null) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(failure, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ if (state.error != null) onRefresh() else { reload++ } }) { Text("重试") }
            }
            PullToRefreshBox(isRefreshing = state.refreshing || loading && offset == 0, onRefresh = ::refresh,
                modifier = Modifier.weight(1f).semantics {
                    customActions = listOf(CustomAccessibilityAction(if (favorites) "刷新我喜欢" else "刷新曲库") { refresh(); true })
                }) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    AnimatedContent(selected, Modifier.fillMaxSize(), transitionSpec = {
                        fadeIn(tween(200, 80)) togetherWith fadeOut(tween(140))
                    }, label = "collection-transition") { destination ->
                        // Exiting details retain their own list, including during rapid reversal.
                        val pageList = remember(destination?.key, searching, submitted) { list }
                        val pageKey = if (searching) "search:$submitted" else if (favorites) collection?.key else destination?.key
                        val pageTracks = tracks.takeIf { loadedKey == pageKey }.orEmpty()
                        if (!favorites && destination == null && !searching) LazyVerticalGrid(GridCells.Adaptive(145.dp), state = shelf,
                            modifier = Modifier.fillMaxSize().testTag("online-shelf"),
                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = bottomInset),
                            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            items(state.library.collections, key = { it.key }) { card ->
                                Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 320f)).clickable {
                                    selectedKey = card.key; submitted = ""; offset = 0; error = null; searchExpanded = false; focus.clearFocus()
                                }.testTag("collection-${card.key}")) {
                                    Cover(card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(1f).then(
                                        if (card.key in visibleCards) Modifier.sharedElement(rememberSharedContentState("cover:${card.key}"),
                                            animatedVisibilityScope = this@AnimatedContent, boundsTransform = { _, _ -> spring(dampingRatio = .9f, stiffness = 320f) }) else Modifier))
                                    Text(card.title, Modifier.padding(top = 10.dp, start = 2.dp),
                                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (card.kind == "album") card.artist.ifBlank { "专辑" } else "歌单 · ${card.count} 首",
                                        Modifier.padding(start = 2.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (state.loaded && state.library.collections.isEmpty() && failure == null) item(span = { GridItemSpan(maxLineSpan) }) {
                                ShelfEmpty("收藏的专辑和你创建的歌单，会出现在这里")
                            }
                        } else LazyColumn(state = pageList, modifier = Modifier.fillMaxSize().testTag("online-tracks"),
                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottomInset)) {
                            if (favorites && pageTracks.isNotEmpty()) item(key = "favorites-album-wall") {
                                FavoritesAlbumWall(pageTracks.take(15))
                            }
                            if (destination != null) item(key = "collection-header") {
                                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Cover(destination.cover, destination.title, Modifier.fillMaxWidth(.68f).widthIn(max = 280.dp).aspectRatio(1f)
                                        .sharedElement(rememberSharedContentState("cover:${destination.key}"), animatedVisibilityScope = this@AnimatedContent,
                                            boundsTransform = { _, _ -> spring(dampingRatio = .9f, stiffness = 320f) }).testTag("collection-cover"))
                                    Text(if (destination.kind == "album") destination.artist else "${destination.count} 首", Modifier.padding(top = 12.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            itemsIndexed(pageTracks, key = { _, it -> it.id }) { index, track ->
                                SongRow(track.song().copy(favorite = track.originalId in likes.ids || favorites && !likes.ready), currentId == "netease:${track.id}", {
                                    if (track.available) onPlay(pageTracks, index) else error = "当前账号暂时无法播放这首歌"
                                }, { onFavorite(track) }, modifier = Modifier.animateItem().alpha(if (track.available) 1f else .5f),
                                    favoriteEnabled = cloud.direct && likes.ready && track.originalId !in likes.pending, showFavorite = !favorites)
                            }
                            if (more && loadedKey == key) item {
                                Spacer(Modifier.fillMaxWidth().height(20.dp))
                            }
                            if (pageTracks.isEmpty() && !loading && !state.refreshing && state.loaded && failure == null) item {
                                ShelfEmpty(if (favorites) "还没有红心歌曲" else "没有找到歌曲")
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Uses the liked playlist's newest-first prefix, not the unordered like-ID snapshot. */
@Composable private fun FavoritesAlbumWall(tracks: List<CloudTrack>) {
    GlassSurface(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp).testTag("favorites-album-wall")
        .clearAndSetSemantics { contentDescription = "最近喜欢的 ${tracks.size} 首歌曲封面" }, shape = RoundedCornerShape(28.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val edge = ((maxWidth - 48.dp) / 5).coerceAtMost(68.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.chunked(5).forEachIndexed { row, covers ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.offset(x = if (row == 1) 3.dp else (-2).dp)) {
                        covers.forEachIndexed { column, track ->
                            Cover(track.cover, track.title, Modifier.size(edge).testTag("favorite-wall-${track.id}").graphicsLayer {
                                rotationZ = ((row * 5 + column) % 5 - 2) * 2f
                            })
                        }
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
