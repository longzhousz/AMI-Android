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
import androidx.compose.ui.graphics.graphicsLayer
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
        restoreLyrics = { model.clearLyrics(song) },
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
    val latestSongId by rememberUpdatedState(song.id)
    val latestActions by rememberUpdatedState(actions)
    var showTranslation by rememberSaveable { mutableStateOf(true) }
    var lyricMenu by remember { mutableStateOf(false) }
    val latestCover by rememberUpdatedState(song.cover)
    val artworkMemory = remember { ArtworkMemory { latestCover } }
    LaunchedEffect(song.id) { lyricMenu = false }
    MaterialTheme(colorScheme = PlayerColors) {
        Surface(Modifier.fillMaxSize().testTag("full-player"), color = Color.Transparent, contentColor = Color.White) {
            PlayerBackdrop(song.cover)
            PlayerArtworkScene(song, artworkMemory, header = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-header").then(dragHandle)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放页", actions.close)
                    Spacer(Modifier.weight(1f))
                    Box {
                        ActionIcon(Icons.Rounded.MoreHoriz, "歌词选项", { lyricMenu = true }, tint = Color.White.copy(alpha = .7f))
                        DropdownMenu(lyricMenu, { lyricMenu = false }) {
                            DropdownMenuItem(text = { Text("为这首歌导入歌词") }, onClick = { lyricMenu = false; actions.lyricFile() })
                            if (song.manualLyrics) DropdownMenuItem(text = { Text("恢复自动匹配") }, onClick = { lyricMenu = false; actions.restoreLyrics() })
                            if (!translation.isNullOrEmpty()) DropdownMenuItem(text = { Text("重新翻译") }, onClick = { lyricMenu = false; actions.retranslate() }, enabled = !translating)
                        }
                    }
                }
            }, lyrics = {
                AnimatedContent(PlayerStage(song, translation.takeIf { showTranslation }, preparedLines, lyricsReady),
                    Modifier.fillMaxSize(), contentKey = { it.song.id }, transitionSpec = {
                        (fadeIn(tween(260, 40)) + scaleIn(tween(340), initialScale = .985f)) togetherWith fadeOut(tween(160))
                    }, label = "player-lyrics") { stage ->
                    val stageLines = stage.lines ?: remember(stage.song.lyrics) { Lyrics.parse(stage.song.lyrics) }
                    val lastPosition = remember(stage.song.id) { longArrayOf(0L) }
                    val stageClock: () -> Long = {
                        if (stage.song.id == latestSongId) lyricPosition().also { lastPosition[0] = it } else lastPosition[0]
                    }
                    if (!stage.ready) Box(Modifier.fillMaxSize())
                    else if (stageLines.isNotEmpty()) LyricsStage(stage.song.id, stageLines, stage.translation, stageClock,
                        { if (stage.song.id == latestSongId) latestActions.seek(it) }, Modifier.fillMaxSize())
                    else EmptyLyrics()
                }
            }, controls = { lyricsMode, progress, thumbnail, toggle ->
                PlayerControls(song, playback, actions, lyricsMode, progress, thumbnail, translating, translation != null,
                    showTranslation, onTranslation = { if (translation != null) showTranslation = !showTranslation else actions.translate() },
                    onLyrics = toggle, position = lyricPosition, modifier = Modifier.fillMaxWidth())
            })
        }
    }
}

private data class PlayerStage(val song: Song, val translation: List<String>?, val lines: List<LyricLine>?, val ready: Boolean)

@Composable private fun EmptyLyrics() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerControls(
    song: Song, playback: PlaybackState, actions: PlayerActions, showLyrics: Boolean,
    artworkProgress: State<Float>, thumbnail: Modifier,
    translating: Boolean, translated: Boolean, showTranslation: Boolean, onTranslation: () -> Unit,
    onLyrics: () -> Unit, position: () -> Long, modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val horizontal = if (maxWidth < 300.dp) 16.dp else 28.dp
        val compact = maxWidth - horizontal * 2 < 264.dp
        val iconSize = if (compact) 40.dp else 48.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = horizontal).padding(bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(thumbnail)
                Spacer(Modifier.width(12.dp))
                AnimatedContent(song.title to song.artist, Modifier.weight(1f).graphicsLayer {
                    translationX = -60.dp.toPx() * (1f - artworkProgress.value)
                }, transitionSpec = { AmiMotion.page(true) }, label = "player-track") { track ->
                    Column {
                        Text(track.first, fontSize = 22.sp, lineHeight = 28.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.second, Modifier.padding(top = 4.dp), fontSize = 13.sp, lineHeight = 19.sp,
                            color = Color.White.copy(alpha = .56f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ActionIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (song.favorite) "取消喜欢" else "加入喜欢", actions.favorite,
                    tint = Color.White.copy(alpha = if (song.favorite) 1f else .55f))
            }
            val duration = playback.duration.takeIf { it > 0 } ?: song.duration
            PlayerTimeline(song.id, duration, playback.buffering, position, actions.seek)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides iconSize) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Rounded.Shuffle, if (playback.shuffle) "关闭随机播放" else "开启随机播放", actions.shuffle,
                        tint = Color.White.copy(alpha = if (playback.shuffle) 1f else .4f), modifier = Modifier.size(iconSize))
                    ActionIcon(Icons.Rounded.SkipPrevious, "上一首", actions.previous, modifier = Modifier.size(iconSize))
                    FilledIconButton(actions.toggle, Modifier.size(if (compact) 66.dp else 72.dp), shape = CircleShape) {
                        AnimatedContent(playback.playing, transitionSpec = {
                            (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .8f)) togetherWith
                                (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = .8f))
                        }, label = "transport-toggle") { playing ->
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(36.dp))
                        }
                    }
                    ActionIcon(Icons.Rounded.SkipNext, "下一首", actions.next, modifier = Modifier.size(iconSize))
                    ActionIcon(if (playback.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (playback.repeat) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" }, actions.repeat,
                        tint = Color.White.copy(alpha = if (playback.repeat > 0) 1f else .4f), modifier = Modifier.size(iconSize))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onLyrics, contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = .10f))) {
                    Icon(if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp)); Text(if (showLyrics) "封面" else "歌词", fontSize = 12.sp)
                }
                if (showLyrics && song.lyrics.isNotBlank()) TextButton(onTranslation, enabled = !translating,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = if (translated && !showTranslation) .45f else .85f))) {
                    Icon(Icons.Rounded.Translate, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                    Text(if (translated) "译文" else "翻译", fontSize = 12.sp)
                } else Spacer(Modifier.weight(1f))
                TextButton(actions.queue, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.QueueMusic, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text("队列", fontSize = 12.sp)
                }
            }
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
            Text(durationLabel(displayed.toLong()), fontSize = 11.sp, color = Color.White.copy(alpha = .45f))
            Spacer(Modifier.weight(1f))
            if (buffering) {
                Text("正在缓冲…", fontSize = 10.sp, color = Color.White.copy(alpha = .4f))
                Spacer(Modifier.weight(1f))
            }
            Text(durationLabel(duration), fontSize = 11.sp, color = Color.White.copy(alpha = .45f))
        }
    }
}
