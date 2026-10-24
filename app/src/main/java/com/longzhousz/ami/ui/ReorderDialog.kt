package com.longzhousz.ami.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.longzhousz.ami.core.Album
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable fun ReorderDialog(album: Album, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var songs by remember(album.key) { mutableStateOf(album.songs) }
    fun move(from: Int, to: Int) {
        if (from !in songs.indices || to !in songs.indices) return
        songs = songs.toMutableList().apply { add(to, removeAt(from)) }
    }
    val density = LocalDensity.current
    val rowHeight = with(density) { 64.dp.toPx() }
    val currentSongs by rememberUpdatedState(songs)
    val haptics = LocalHapticFeedback.current
    var floatingId by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var startIndex by remember { mutableIntStateOf(0) }
    var distance by remember { mutableFloatStateOf(0f) }
    fun finishDrag() {
        dragging = false
        val index = currentSongs.indexOfFirst { it.id == floatingId }
        if (abs(distance + (startIndex - index) * rowHeight) < .5f) floatingId = null
    }
    AlertDialog(onDismiss, title = { Text("调整曲序") }, text = {
        Column {
            Text("长按手柄拖动，也可以点击箭头调整。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 12.dp)) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    val floating = floatingId == song.id
                    val held = floating && dragging
                    val offset = if (held) distance + (startIndex - index) * rowHeight else 0f
                    val placement = animateFloatAsState(offset, if (held) snap() else spring(dampingRatio = .9f, stiffness = 500f),
                        finishedListener = { if (!dragging && floatingId == song.id && it == 0f) floatingId = null }, label = "drag-release")
                    val background by animateColorAsState(if (floating) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, label = "drag-highlight")
                    Row(Modifier.fillMaxWidth().height(64.dp)
                        .animateItem(placementSpec = if (floating) null else spring(stiffness = 500f))
                        .zIndex(if (floating) 1f else 0f)
                        .graphicsLayer { translationY = if (held) offset else placement.value; shadowElevation = if (floating) 6.dp.toPx() else 0f; shape = RoundedCornerShape(12.dp); clip = true }
                        .testTag("reorder-row-${song.id}")
                        .background(background), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DragHandle, "长按拖动 ${song.title}", Modifier.size(36.dp).pointerInput(song.id, rowHeight) {
                            detectDragGesturesAfterLongPress(onDragStart = {
                                floatingId = song.id; dragging = true; distance = 0f
                                startIndex = currentSongs.indexOfFirst { it.id == song.id }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }, onDragEnd = ::finishDrag, onDragCancel = ::finishDrag, onDrag = { change, drag ->
                                change.consume()
                                distance += drag.y
                                val from = currentSongs.indexOfFirst { it.id == song.id }
                                val to = (startIndex + distance / rowHeight).roundToInt().coerceIn(currentSongs.indices)
                                if (from != to) move(from, to)
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
