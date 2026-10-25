package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.LyricLine
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.durationLabel
import com.longzhousz.ami.playback.PlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class PlayerActions(
    val close: () -> Unit = {}, val queue: () -> Unit = {}, val lyricFile: () -> Unit = {},
    val favorite: () -> Unit = {}, val seek: (Long) -> Unit = {}, val toggle: () -> Unit = {},
    val previous: () -> Unit = {}, val next: () -> Unit = {}, val shuffle: () -> Unit = {},
    val repeat: () -> Unit = {}, val translate: () -> Unit = {}, val retranslate: () -> Unit = translate, val restoreLyrics: () -> Unit = {},
    val sleep: (Int) -> Unit = {},
)

@Composable fun PlayerScreen(song: Song, model: AmiViewModel, onClose: () -> Unit, onQueue: () -> Unit, onLyricFile: () -> Unit, dragHandle: Modifier = Modifier) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val matched = lyrics.songId == song.id && lyrics.revision == song.lyricsVersion
    val document = lyrics.document.takeIf { matched }
    val detailed = song.copy(lyrics = document?.original.orEmpty(), lyricTranslation = document?.translated.orEmpty())
    val translation = lyrics.translation.takeIf { matched }
    val translating = matched && lyrics.translating
    val clock = rememberPlaybackClock(model.player, playback)
    PlayerContent(detailed, playback, translation, translating, PlayerActions(
        close = onClose, queue = onQueue, lyricFile = onLyricFile, favorite = { model.favorite(song) },
        seek = { if (model.player.state.value.id == song.id) model.act("player.seek") { model.player.seek(it) } },
        toggle = { model.act("player.toggle") { model.player.toggle() } },
        previous = { model.act("player.previous") { model.player.previous() } },
        next = { model.act("player.next") { model.player.next() } },
        shuffle = { model.act("player.shuffle") { model.player.shuffle() } },
        repeat = { model.act("player.repeat") { model.player.repeat() } },
        translate = { model.translate(song) }, retranslate = { model.translate(song, force = true) },
        restoreLyrics = { model.clearLyrics(song) }, sleep = model::sleep,
    ), lyricPosition = { clock.value }, dragHandle = dragHandle, preparedLines = document?.lines, lyricsReady = document != null)
}

private val PlayerColors = darkColorScheme(
    primary = Color.White, onPrimary = Color(0xFF262631), primaryContainer = Color(0xFF424351),
    onPrimaryContainer = Color.White, surface = Color(0xFF252630), onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBFC0C9), surfaceContainer = Color(0xFF30313D),
)

@Composable internal fun PlayerContent(
    song: Song, playback: PlaybackState, translation: List<String>?, translating: Boolean,
    actions: PlayerActions, lyricPosition: () -> Long = { playback.position },
    dragHandle: Modifier = Modifier,
    preparedLines: List<LyricLine>? = null,
    lyricsReady: Boolean = true,
) {
    var showLyrics by rememberSaveable { mutableStateOf(song.hasLyrics) }
    val latestSongId by rememberUpdatedState(song.id)
    val latestActions by rememberUpdatedState(actions)
    var showTranslation by rememberSaveable { mutableStateOf(true) }
    var sleepDialog by remember { mutableStateOf(false) }
    var lyricMenu by remember { mutableStateOf(false) }
    val latestCover by rememberUpdatedState(song.cover)
    val artworkMemory = remember { ArtworkMemory { latestCover } }
    LaunchedEffect(song.id) { lyricMenu = false }
    MaterialTheme(colorScheme = PlayerColors) {
        Surface(Modifier.fillMaxSize().testTag("full-player"), color = Color.Transparent, contentColor = Color.White) {
            PlayerBackdrop(song.cover)
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val landscape = maxWidth > 600.dp && maxWidth > maxHeight
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().testTag("player-header").then(dragHandle)
                        .padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放页", actions.close)
                        Spacer(Modifier.weight(1f))
                        ActionIcon(Icons.Rounded.Bedtime, "定时停止", { sleepDialog = true }, tint = Color.White.copy(alpha = .7f))
                        Box {
                            ActionIcon(Icons.Rounded.MoreHoriz, "歌词选项", { lyricMenu = true }, tint = Color.White.copy(alpha = .7f))
                            DropdownMenu(lyricMenu, { lyricMenu = false }) {
                                DropdownMenuItem(text = { Text("为这首歌导入歌词") }, onClick = { lyricMenu = false; actions.lyricFile() })
                                if (song.manualLyrics) DropdownMenuItem(text = { Text("恢复自动匹配") }, onClick = { lyricMenu = false; actions.restoreLyrics() })
                                if (!translation.isNullOrEmpty()) DropdownMenuItem(text = { Text("重新翻译") }, onClick = { lyricMenu = false; actions.retranslate() }, enabled = !translating)
                            }
                        }
                    }
                    val stage: @Composable (Modifier) -> Unit = { modifier ->
                        AnimatedContent(PlayerStage(song, showLyrics, translation.takeIf { showTranslation }, preparedLines, lyricsReady), modifier,
                            contentKey = { it.song.id to it.lyrics }, transitionSpec = {
                                (fadeIn(tween(240, 40)) + scaleIn(tween(320), initialScale = .97f)) togetherWith
                                    (fadeOut(tween(160)) + scaleOut(tween(240), targetScale = 1.02f))
                            }, label = "player-stage") { stage ->
                            val stageLines = stage.lines ?: remember(stage.song.lyrics) { Lyrics.parse(stage.song.lyrics) }
                            val lastPosition = remember(stage.song.id) { longArrayOf(0L) }
                            val stageClock: () -> Long = {
                                if (stage.song.id == latestSongId) lyricPosition().also { lastPosition[0] = it } else lastPosition[0]
                            }
                            if (stage.lyrics && !stage.ready) Box(Modifier.fillMaxSize())
                            else if (stage.lyrics && stageLines.isNotEmpty()) LyricsStage(stage.song.id, stageLines, stage.translation, stageClock,
                                { if (stage.song.id == latestSongId) latestActions.seek(it) }, Modifier.fillMaxSize())
                            else if (stage.lyrics) EmptyLyrics()
                            else BoxWithConstraints(Modifier.fillMaxSize().then(dragHandle).padding(28.dp), contentAlignment = Alignment.Center) {
                                Cover(stage.song.cover, stage.song.album, Modifier.size(minOf(maxWidth, maxHeight)).testTag("player-cover"), artworkMemory)
                            }
                        }
                    }
                    val controls: @Composable (Modifier) -> Unit = { modifier ->
                        PlayerControls(song, playback, actions, showLyrics, translating, translation != null,
                            showTranslation, onTranslation = { if (translation != null) showTranslation = !showTranslation else actions.translate() },
                            onLyrics = { showLyrics = !showLyrics }, position = lyricPosition, modifier = modifier)
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
                listOf(15, 30, 45, 60, 90, 0).forEach { minutes -> TextButton({ actions.sleep(minutes); sleepDialog = false }, Modifier.fillMaxWidth()) { Text(if (minutes == 0) "关闭定时停止" else "$minutes 分钟") } }
            }
        }, confirmButton = { TextButton({ sleepDialog = false }) { Text("取消") } })
    }
}

private data class PlayerStage(val song: Song, val lyrics: Boolean, val translation: List<String>?, val lines: List<LyricLine>?, val ready: Boolean)

@Composable private fun EmptyLyrics() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerControls(
    song: Song, playback: PlaybackState, actions: PlayerActions, showLyrics: Boolean,
    translating: Boolean, translated: Boolean, showTranslation: Boolean, onTranslation: () -> Unit,
    onLyrics: () -> Unit, position: () -> Long, modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showLyrics) { Cover(song.cover, song.album, Modifier.size(46.dp)); Spacer(Modifier.width(12.dp)) }
            AnimatedContent(song.title to song.artist, Modifier.weight(1f), transitionSpec = { AmiMotion.page(true) }, label = "player-track") { track ->
                Column {
                    Text(track.first, fontSize = if (showLyrics) 19.sp else 25.sp, lineHeight = if (showLyrics) 25.sp else 32.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.second, Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ActionIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (song.favorite) "取消喜欢" else "加入喜欢", actions.favorite, tint = Color.White.copy(alpha = if (song.favorite) 1f else .55f))
        }
        val duration = playback.duration.takeIf { it > 0 } ?: song.duration
        PlayerTimeline(song.id, duration, playback.buffering, position, actions.seek)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Rounded.Shuffle, if (playback.shuffle) "关闭随机播放" else "开启随机播放", actions.shuffle, tint = Color.White.copy(alpha = if (playback.shuffle) 1f else .4f))
            ActionIcon(Icons.Rounded.SkipPrevious, "上一首", actions.previous)
            FilledIconButton(actions.toggle, Modifier.size(62.dp), shape = CircleShape) {
                AnimatedContent(playback.playing, transitionSpec = {
                    (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .8f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = .8f))
                }, label = "transport-toggle") { playing ->
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(34.dp))
                }
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
                Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (translated) "译文" else "翻译", fontSize = 12.sp, maxLines = 1)
            }
            TextButton(actions.queue, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("队列", fontSize = 12.sp, maxLines = 1) }
        }
    }
}

private data class SeekPreview(val serial: Int, val position: Long)

/** Frame-clock reads stop here: artwork, buttons and the library never observe each tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerTimeline(songId: String, duration: Long, buffering: Boolean, position: () -> Long, onSeek: (Long) -> Unit) {
    var seeking by remember(songId) { mutableStateOf<Float?>(null) }
    var pendingSeek by remember(songId) { mutableStateOf<SeekPreview?>(null) }
    var seekSerial by remember(songId) { mutableIntStateOf(0) }
    val currentPosition by rememberUpdatedState(position)
    LaunchedEffect(pendingSeek) {
        pendingSeek?.let { request ->
            withTimeoutOrNull(3000) { snapshotFlow { currentPosition() }.first { abs(it - request.position) < 500 } }
            if (pendingSeek === request) pendingSeek = null
        }
    }
    val displayed = (seeking ?: pendingSeek?.position?.toFloat() ?: position().toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat())
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .8f), inactiveTrackColor = Color.White.copy(alpha = .15f))
    Column {
        Slider(value = displayed,
            onValueChange = { pendingSeek = null; seeking = it }, onValueChangeFinished = {
                seeking?.toLong()?.let { pendingSeek = SeekPreview(++seekSerial, it); onSeek(it) }
                seeking = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0, colors = colors,
            thumb = {
                Box(Modifier.width(8.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(Color.White.copy(alpha = if (duration > 0) 1f else .3f), CircleShape))
                }
            },
            track = { SliderDefaults.Track(it, Modifier.height(3.dp), enabled = duration > 0, colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag("playback-seek"))
        Row(Modifier.fillMaxWidth()) {
            Text(durationLabel(displayed.toLong()), fontSize = 10.sp, color = Color.White.copy(alpha = .45f))
            Spacer(Modifier.weight(1f))
            if (buffering) {
                Text("正在缓冲…", fontSize = 10.sp, color = Color.White.copy(alpha = .4f))
                Spacer(Modifier.weight(1f))
            }
            Text(durationLabel(duration), fontSize = 10.sp, color = Color.White.copy(alpha = .45f))
        }
    }
}
