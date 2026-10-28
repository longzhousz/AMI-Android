package com.longzhousz.ami.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.NeteaseLoginActivity
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.CloudMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect

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

@Composable internal fun OnlineLibraryScreen(model: AmiViewModel, favorites: Boolean) {
    val configured by model.cloud.configured.collectAsStateWithLifecycle()
    val state by model.onlineLibrary.collectAsStateWithLifecycle()
    val playback by model.player.state.collectAsStateWithLifecycle()
    val signIn = rememberNeteaseLogin(model)
    LaunchedEffect(configured) {
        if (!configured) model.resetOnlineLibrary()
        else if (!state.loaded) model.refreshOnlineLibrary()
    }
    OnlineLibraryContent(model.cloud, state, favorites, playback.id, model::refreshOnlineLibrary, model::playCloud, signIn)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OnlineLibraryContent(
    cloud: CloudMusic, state: CloudLibraryState, favorites: Boolean, currentId: String?,
    onRefresh: () -> Unit, onPlay: (List<CloudTrack>, Int) -> Unit, onLogin: () -> Unit,
) {
    val configured by cloud.configured.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<List<CloudTrack>>(emptyList()) }
    var next by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val shelf = rememberLazyGridState()
    val list = rememberLazyListState()
    val selected = state.library.collections.firstOrNull { it.key == selectedKey }
    val collection = if (favorites) state.library.liked else selected
    val searching = submitted.isNotBlank()
    val showingTracks = favorites || selected != null || searching
    val key = if (searching) "search:$submitted" else collection?.key
    val visibleTracks = tracks.takeIf { key == loadedKey }.orEmpty()
    fun back() { selectedKey = null; submitted = ""; query = ""; offset = 0; error = null }
    fun refresh() {
        if (loading || state.refreshing) return
        if (showingTracks) { cloud.refreshCatalog(); offset = 0; reload++ }
        if (selected == null && !searching) onRefresh()
    }
    BackHandler(selected != null || searching) { back() }
    LaunchedEffect(configured, key, offset, reload) {
        if (!configured) { tracks = emptyList(); loadedKey = null; offset = 0; loading = false; return@LaunchedEffect }
        if (key == null) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        if (loadedKey != key) { tracks = emptyList(); more = false; list.scrollToItem(0) }
        try {
            val page = if (searching) cloud.search(submitted, offset) else cloud.tracks(collection!!, offset)
            tracks = (if (offset == 0 || loadedKey != key) emptyList() else tracks).plus(page.tracks).distinctBy { it.id }
            loadedKey = key; next = page.nextOffset; more = page.more
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message?.take(150) ?: "音乐暂时无法载入" }
        finally { if (currentCoroutineContext().isActive) loading = false }
    }
    LaunchedEffect(key, list) {
        snapshotFlow {
            showingTracks && loadedKey == key && more && !loading && next > offset && list.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) offset = next }
    }
    Column(Modifier.fillMaxSize().testTag(if (favorites) "online-favorites" else "online-library")) {
        Row(Modifier.fillMaxWidth().padding(start = if (selected != null || searching) 8.dp else 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selected != null || searching) ActionIcon(Icons.Rounded.ArrowBack, "返回曲库", ::back)
            Text(if (favorites) "我喜欢" else if (searching) "搜索结果" else selected?.title ?: "曲库",
                style = if (selected == null && !searching) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (!configured) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (favorites) Icons.Rounded.FavoriteBorder else Icons.Rounded.LibraryMusic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("登录后，音乐就在这里", style = MaterialTheme.typography.titleMedium)
                    Button(onLogin) { Text("登录网易云") }
                }
            }
        } else {
            if (!favorites && selected == null) OutlinedTextField(query, { query = it },
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp).testTag("online-search"),
                placeholder = { Text("搜索歌曲、艺人") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "清空搜索", { query = ""; submitted = ""; offset = 0 }) },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus(); submitted = query.trim(); selectedKey = null; offset = 0; reload++
                }))
            val failure = error ?: state.error
            if (failure != null) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(failure, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ if (state.error != null) onRefresh() else { reload++ } }) { Text("重试") }
            }
            PullToRefreshBox(isRefreshing = state.refreshing || loading && offset == 0, onRefresh = ::refresh,
                modifier = Modifier.weight(1f).semantics {
                    customActions = listOf(CustomAccessibilityAction(if (favorites) "刷新我喜欢" else "刷新曲库") { refresh(); true })
                }) {
                if (!showingTracks) LazyVerticalGrid(GridCells.Adaptive(145.dp), state = shelf,
                    modifier = Modifier.fillMaxSize().testTag("online-shelf"),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(state.library.collections, key = { it.key }) { card ->
                        Column(Modifier.animateItem(placementSpec = spring(stiffness = 320f)).clip(RoundedCornerShape(14.dp)).clickable {
                            selectedKey = card.key; submitted = ""; offset = 0; error = null
                        }.testTag("collection-${card.key}")) {
                            Cover(card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 14.dp)
                            Text(card.title, Modifier.padding(top = 10.dp, start = 2.dp).heightIn(min = 46.dp),
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (card.kind == "album") card.artist.ifBlank { "专辑" } else "歌单 · ${card.count} 首",
                                Modifier.padding(start = 2.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.loaded && state.library.collections.isEmpty() && failure == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfEmpty("收藏的专辑和你创建的歌单，会出现在这里")
                    }
                } else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("online-tracks"),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                    if (selected != null) item(key = "collection-header") {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(selected.cover, selected.title, Modifier.size(164.dp), cornerRadius = 18.dp)
                            Text(if (selected.kind == "album") selected.artist else "${selected.count} 首", Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(visibleTracks, key = { _, it -> it.id }) { index, track ->
                        SongRow(track.song(), currentId == "netease:${track.id}", {
                            if (track.available) onPlay(visibleTracks, index) else error = "当前账号暂时无法播放这首歌"
                        }, {}, modifier = Modifier.animateItem().alpha(if (track.available) 1f else .5f))
                    }
                    if (more && loadedKey == key) item {
                        Spacer(Modifier.fillMaxWidth().height(20.dp))
                    }
                    if (visibleTracks.isEmpty() && !loading && !state.refreshing && state.loaded && failure == null) item {
                        ShelfEmpty(if (favorites) "还没有红心歌曲" else "没有找到歌曲")
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
