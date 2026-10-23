package com.longzhousz.ami.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.core.Album
import kotlin.math.abs

@Composable fun ReorderDialog(album: Album, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var songs by remember(album.key) { mutableStateOf(album.songs) }
    fun move(from: Int, to: Int) {
        if (from !in songs.indices || to !in songs.indices) return
        songs = songs.toMutableList().apply { add(to, removeAt(from)) }
    }
    val density = LocalDensity.current
    val rowHeight = with(density) { 64.dp.toPx() }
    val currentSongs by rememberUpdatedState(songs)
    AlertDialog(onDismiss, title = { Text("调整曲序") }, text = {
        Column {
            Text("长按手柄拖动，也可以点击箭头调整。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 12.dp)) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DragHandle, "长按拖动 ${song.title}", Modifier.size(36.dp).pointerInput(song.id) {
                            var distance = 0f
                            detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, drag ->
                                change.consume(); distance += drag.y
                                if (abs(distance) > rowHeight * .7f) {
                                    val position = currentSongs.indexOfFirst { it.id == song.id }
                                    move(position, (position + if (distance > 0) 1 else -1).coerceIn(currentSongs.indices)); distance = 0f
                                }
                            })
                        }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(song.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ActionIcon(Icons.Rounded.ArrowUpward, "上移 ${song.title}", { move(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(36.dp))
                        ActionIcon(Icons.Rounded.ArrowDownward, "下移 ${song.title}", { move(index, index + 1) }, enabled = index < songs.lastIndex, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ onSave(songs.map { it.id }) }) { Text("保存曲序") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
