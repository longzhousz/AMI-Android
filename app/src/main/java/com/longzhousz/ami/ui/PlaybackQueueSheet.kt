package com.longzhousz.ami.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.launch

/** The dialog owns its backdrop. Never draw another window's GraphicsLayer in a moving sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlaybackQueueSheet(
    ids: List<String>, currentId: String?, cover: String?, songFor: (String) -> Song?,
    onDismiss: () -> Unit, onPlay: (Int) -> Unit, onFavorite: (Song) -> Unit,
    favoriteEnabled: (Song) -> Boolean = { true },
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close = {
        if (!closing) {
            closing = true
            scope.launch { sheet.hide() }.invokeOnCompletion {
                closing = false
                if (!sheet.isVisible) onDismiss()
            }
        }
        Unit
    }
    MaterialTheme(colorScheme = PlayerColors) {
        ModalBottomSheet(onDismiss, sheetState = sheet, dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = PlayerColors.surface, contentColor = Color.White) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                GlassBackdropHost(Modifier.fillMaxWidth().height((maxHeight * .82f).coerceAtMost(640.dp)).testTag("queue-surface"),
                    background = {
                        CompositionLocalProvider(LocalVisualsActive provides false) { PlayerBackdrop(cover, dark = true) }
                    }) {
                    Column(Modifier.fillMaxSize()) {
                        BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally).testTag("queue-drag-handle"), color = Color.White.copy(alpha = .3f))
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("播放队列 · ${ids.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            ActionIcon(Icons.Rounded.Close, "关闭播放队列", close)
                        }
                        val list = rememberLazyListState((ids.indexOf(currentId) - 1).coerceAtLeast(0))
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue-tracks"), state = list,
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                            itemsIndexed(ids, key = { index, id -> "$index:$id" }) { index, id ->
                                songFor(id)?.let { song ->
                                    SongRow(song, id == currentId, {
                                        if (!closing) { onPlay(index); close() }
                                    }, { if (!closing) onFavorite(song) }, number = index + 1,
                                        modifier = Modifier.testTag("queue-track-$index"), favoriteEnabled = !closing && favoriteEnabled(song))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
