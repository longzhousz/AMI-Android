package com.longzhousz.ami.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.durationLabel
import com.longzhousz.ami.playback.PlaybackState
import kotlinx.coroutines.delay

internal data class PlayerActions(
    val close: () -> Unit = {}, val queue: () -> Unit = {}, val lyricFile: () -> Unit = {},
    val favorite: () -> Unit = {}, val seek: (Long) -> Unit = {}, val toggle: () -> Unit = {},
    val previous: () -> Unit = {}, val next: () -> Unit = {}, val shuffle: () -> Unit = {},
    val repeat: () -> Unit = {}, val translate: () -> Unit = {}, val restoreLyrics: () -> Unit = {},
    val sleep: (Int) -> Unit = {},
)

@Composable fun PlayerScreen(song: Song, model: AmiViewModel, onClose: () -> Unit, onQueue: () -> Unit, onLyricFile: () -> Unit) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val translation by model.translations.collectAsStateWithLifecycle()
    val translating by model.translating.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lyricClock = produceState(playback.position, model.player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = model.player.currentPosition()
                delay(if (model.player.state.value.playing) 50 else 250)
            }
        }
    }
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    PlayerContent(song, playback, translation, translating, PlayerActions(
        close = onClose, queue = onQueue, lyricFile = onLyricFile, favorite = { model.favorite(song) },
        seek = { model.act("player.seek") { model.player.seek(it) } },
        toggle = { model.act("player.toggle") { model.player.toggle() } },
        previous = { model.act("player.previous") { model.player.previous() } },
        next = { model.act("player.next") { model.player.next() } },
        shuffle = { model.act("player.shuffle") { model.player.shuffle() } },
        repeat = { model.act("player.repeat") { model.player.repeat() } },
        translate = { model.translate(song) }, restoreLyrics = { model.clearLyrics(song) }, sleep = model::sleep,
    ), lyricPosition = { lyricClock.value })
}

private val PlayerColors = darkColorScheme(
    primary = Color.White, onPrimary = Color(0xFF262631), primaryContainer = Color(0xFF424351),
    onPrimaryContainer = Color.White, surface = Color(0xFF252630), onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBFC0C9), surfaceContainer = Color(0xFF30313D),
)

@Composable internal fun PlayerContent(
    song: Song, playback: PlaybackState, translation: List<String>?, translating: Boolean,
    actions: PlayerActions, lyricPosition: () -> Long = { playback.position },
) {
    var showLyrics by rememberSaveable { mutableStateOf(song.lyrics.isNotBlank()) }
    var showTranslation by rememberSaveable { mutableStateOf(true) }
    var sleepDialog by remember { mutableStateOf(false) }
    var lyricMenu by remember { mutableStateOf(false) }
    val lines = remember(song.lyrics) { Lyrics.parse(song.lyrics) }
    val close by rememberUpdatedState(actions.close)
    MaterialTheme(colorScheme = PlayerColors) {
        Surface(Modifier.fillMaxSize().testTag("full-player"), color = Color.Transparent, contentColor = Color.White) {
            PlayerBackdrop(song.cover)
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val landscape = maxWidth > 600.dp && maxWidth > maxHeight
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().testTag("player-header").pointerInput(Unit) {
                        var distance = 0f
                        val threshold = 56.dp.toPx()
                        detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragEnd = { if (distance > threshold) close() },
                            onVerticalDrag = { change, delta ->
                                if (delta > 0 || distance > 0) { change.consume(); distance = (distance + delta).coerceAtLeast(0f) }
                            })
                    }.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放页", actions.close)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("正在聆听", color = Color.White.copy(alpha = .5f), fontSize = 10.sp, letterSpacing = 2.sp)
                            Text(song.album, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ActionIcon(Icons.Rounded.Bedtime, "定时停止", { sleepDialog = true }, tint = Color.White.copy(alpha = .7f))
                        Box {
                            ActionIcon(Icons.Rounded.MoreHoriz, "歌词选项", { lyricMenu = true }, tint = Color.White.copy(alpha = .7f))
                            DropdownMenu(lyricMenu, { lyricMenu = false }) {
                                DropdownMenuItem(text = { Text("为这首歌导入歌词") }, onClick = { lyricMenu = false; actions.lyricFile() })
                                if (song.manualLyrics) DropdownMenuItem(text = { Text("恢复自动匹配") }, onClick = { lyricMenu = false; actions.restoreLyrics() })
                                if (!translation.isNullOrEmpty()) DropdownMenuItem(text = { Text(if (showTranslation) "隐藏译文" else "显示译文") }, onClick = { lyricMenu = false; showTranslation = !showTranslation })
                                if (!translation.isNullOrEmpty()) DropdownMenuItem(text = { Text("重新翻译") }, onClick = { lyricMenu = false; actions.translate() }, enabled = !translating)
                                DropdownMenuItem(text = { Text(song.neteaseId?.let { "网易云 ID：$it" } ?: "没有网易云 ID，可手动选择文件") }, onClick = {}, enabled = false)
                                if (song.lyricsSource.isNotBlank()) DropdownMenuItem(text = { Text("来源：${song.lyricsSource}") }, onClick = {}, enabled = false)
                            }
                        }
                    }
                    val stage: @Composable (Modifier) -> Unit = { modifier ->
                        Crossfade(showLyrics, modifier, animationSpec = tween(350), label = "cover-lyrics") { lyrics ->
                            if (lyrics && lines.isNotEmpty()) LyricsStage(song.id, lines, translation.takeIf { showTranslation }, lyricPosition, actions.seek, Modifier.fillMaxSize())
                            else if (lyrics) EmptyLyrics(actions.lyricFile)
                            else BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                                Cover(song.cover, song.album, Modifier.size(minOf(maxWidth, maxHeight)).testTag("player-cover"))
                            }
                        }
                    }
                    val controls: @Composable (Modifier) -> Unit = { modifier ->
                        PlayerControls(song, playback, actions, showLyrics, translating, translation != null,
                            showTranslation, onTranslation = { if (translation != null) showTranslation = !showTranslation else actions.translate() },
                            onLyrics = { showLyrics = !showLyrics }, modifier = modifier)
                    }
                    if (landscape) Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (!showLyrics) stage(Modifier.weight(.45f).fillMaxHeight())
                        // In landscape only this pane scrolls when accessibility text is large.
                        Column(Modifier.weight(.45f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                            controls(Modifier.fillMaxWidth())
                        }
                        if (showLyrics) stage(Modifier.weight(.55f).fillMaxHeight())
                    } else {
                        stage(Modifier.weight(1f).fillMaxWidth())
                        controls(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (sleepDialog) AlertDialog({ sleepDialog = false }, title = { Text("定时停止") }, text = {
            Column {
                Text("时间到后，音乐将轻轻暂停。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(15, 30, 45, 60, 90, 0).forEach { minutes -> TextButton({ actions.sleep(minutes); sleepDialog = false }, Modifier.fillMaxWidth()) { Text(if (minutes == 0) "关闭定时停止" else "$minutes 分钟") } }
            }
        }, confirmButton = { TextButton({ sleepDialog = false }) { Text("取消") } })
    }
}

@Composable private fun EmptyLyrics(onLyricFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Lyrics, null, Modifier.size(42.dp), tint = Color.White.copy(alpha = .5f))
        Text("让歌词也陪你听", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
        Text("可在设置中导入网易云歌词文件夹", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onLyricFile) { Text("为这首歌选择歌词文件") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerControls(
    song: Song, playback: PlaybackState, actions: PlayerActions, showLyrics: Boolean,
    translating: Boolean, translated: Boolean, showTranslation: Boolean, onTranslation: () -> Unit,
    onLyrics: () -> Unit, modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showLyrics) { Cover(song.cover, song.album, Modifier.size(46.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = if (showLyrics) 19.sp else 25.sp, lineHeight = if (showLyrics) 25.sp else 32.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ActionIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (song.favorite) "取消喜欢" else "加入喜欢", actions.favorite, tint = Color.White.copy(alpha = if (song.favorite) 1f else .55f))
        }
        var seeking by remember(song.id) { mutableStateOf<Float?>(null) }
        val duration = playback.duration.takeIf { it > 0 } ?: song.duration
        val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .8f), inactiveTrackColor = Color.White.copy(alpha = .15f))
        Slider(value = seeking ?: playback.position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { actions.seek(it.toLong()) }; seeking = null },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0,
            colors = sliderColors,
            thumb = {
                Box(Modifier.width(8.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(Color.White.copy(alpha = if (duration > 0) 1f else .3f), CircleShape))
                }
            },
            track = { SliderDefaults.Track(it, Modifier.height(3.dp), enabled = duration > 0, colors = sliderColors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag("playback-seek"))
        Row(Modifier.fillMaxWidth()) {
            Text(durationLabel(seeking?.toLong() ?: playback.position), fontSize = 10.sp, color = Color.White.copy(alpha = .45f))
            Spacer(Modifier.weight(1f))
            Text(if (playback.buffering) "正在缓冲…" else song.format, fontSize = 10.sp, color = Color.White.copy(alpha = .4f))
            Spacer(Modifier.weight(1f))
            Text(durationLabel(duration), fontSize = 10.sp, color = Color.White.copy(alpha = .45f))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Rounded.Shuffle, if (playback.shuffle) "关闭随机播放" else "开启随机播放", actions.shuffle, tint = Color.White.copy(alpha = if (playback.shuffle) 1f else .4f))
            ActionIcon(Icons.Rounded.SkipPrevious, "上一首", actions.previous)
            FilledIconButton(actions.toggle, Modifier.size(62.dp), shape = CircleShape) {
                Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "暂停" else "播放", Modifier.size(34.dp))
            }
            ActionIcon(Icons.Rounded.SkipNext, "下一首", actions.next)
            ActionIcon(if (playback.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (playback.repeat) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" }, actions.repeat,
                tint = Color.White.copy(alpha = if (playback.repeat > 0) 1f else .4f))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onLyrics, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (showLyrics) Color.White.copy(alpha = .12f) else Color.Transparent)) {
                Icon(if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (showLyrics) "封面" else "歌词", fontSize = 12.sp, maxLines = 1)
            }
            if (showLyrics && song.lyrics.isNotBlank()) TextButton(onTranslation, Modifier.weight(1f), enabled = !translating,
                contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = if (translated && !showTranslation) .45f else 1f))) {
                if (translating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (translating) "翻译中" else if (translated) "译文" else "翻译", fontSize = 12.sp, maxLines = 1)
            }
            TextButton(actions.queue, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("队列", fontSize = 12.sp, maxLines = 1) }
        }
    }
}
