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
import com.longzhousz.ami.core.isCloudSong
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
    val external: Boolean = false, val settings: (() -> Unit)? = null,
    val canSeek: Boolean = true, val canPrevious: Boolean = true, val canNext: Boolean = true, val canToggle: Boolean = true,
    val canFavorite: Boolean = true,
)

@Composable fun PlayerScreen(song: Song, model: AmiViewModel, onClose: () -> Unit, onQueue: () -> Unit, onLyricFile: () -> Unit,
    dragHandle: Modifier = Modifier, lyricsPage: MutableState<Boolean> = rememberSaveable(song.id) { mutableStateOf(false) }) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val qualities by model.cloud.audioQuality.collectAsStateWithLifecycle()
    val likes by model.cloud.likes.collectAsStateWithLifecycle()
    LaunchedEffect(song.isCloudSong(), likes.session) { if (song.isCloudSong()) model.refreshCloudLikes() }
    val matched = lyrics.songId == song.id && lyrics.revision == song.lyricsVersion
    val document = lyrics.document.takeIf { matched }
    val detailed = song.copy(lyrics = document?.original.orEmpty(), lyricTranslation = document?.translated.orEmpty(),
        favorite = if (song.isCloudSong()) song.neteaseId in likes.ids else song.favorite)
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
        canFavorite = !song.isCloudSong() || model.cloud.direct && likes.ready && song.neteaseId !in likes.pending,
    ), lyricPosition = { clock.value }, dragHandle = dragHandle, preparedLines = document?.lines, lyricsReady = document != null,
        audioQuality = if (song.isCloudSong()) qualities[song.id]?.label ?: "待确认" else song.format.ifBlank { "待确认" }, lyricsPage = lyricsPage)
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
    audioQuality: String = "暂不可知",
    lyricsPage: MutableState<Boolean> = rememberSaveable(song.id) { mutableStateOf(false) },
) {
    val latestSongId by rememberUpdatedState(song.id)
    val latestActions by rememberUpdatedState(actions)
    var showTranslation by rememberSaveable { mutableStateOf(true) }
    var lyricMenu by remember { mutableStateOf(false) }
    var pendingSeek by remember(song.id) { mutableStateOf<SeekPreview?>(null) }
    var seekSerial by remember(song.id) { mutableIntStateOf(0) }
    val currentPosition by rememberUpdatedState(lyricPosition)
    LaunchedEffect(pendingSeek) {
        pendingSeek?.let { request ->
            withTimeoutOrNull(3000) { snapshotFlow { currentPosition() }.first { abs(it - request.position) < 500 } }
            if (pendingSeek === request) pendingSeek = null
        }
    }
    val displayedPosition: () -> Long = { pendingSeek?.position ?: lyricPosition() }
    val seek: (Long) -> Unit = { target -> pendingSeek = SeekPreview(++seekSerial, target); actions.seek(target) }
    LaunchedEffect(song.id) { lyricMenu = false }
    MaterialTheme(colorScheme = PlayerColors) {
        Surface(Modifier.fillMaxSize().testTag("full-player"), color = Color.Transparent, contentColor = Color.White) {
            PlayerBackdrop(song.cover)
            PlayerArtworkScene(song, lyricsPage, header = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-header").then(dragHandle)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放页", actions.close)
                    Spacer(Modifier.weight(1f))
                    actions.settings?.let { ActionIcon(Icons.Rounded.Tune,"设置",it,tint=Color.White.copy(alpha=.7f)) }
                    Box {
                        ActionIcon(Icons.Rounded.MoreHoriz, "歌词选项", { lyricMenu = true }, tint = Color.White.copy(alpha = .7f))
                        DropdownMenu(lyricMenu, { lyricMenu = false }) {
                            DropdownMenuItem(text = { Text("当前音质：$audioQuality") }, onClick = {}, enabled = false,
                                leadingIcon = { Icon(Icons.Rounded.HighQuality, null) },
                                colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
                            if (!song.isCloudSong() && !actions.external) DropdownMenuItem(text = { Text("为这首歌导入歌词") }, onClick = { lyricMenu = false; actions.lyricFile() })
                            if (song.manualLyrics && !actions.external) DropdownMenuItem(text = { Text("恢复自动匹配") }, onClick = { lyricMenu = false; actions.restoreLyrics() })
                            if (!translation.isNullOrEmpty()) DropdownMenuItem(text = { Text("重新翻译") }, onClick = { lyricMenu = false; actions.retranslate() }, enabled = !translating)
                        }
                    }
                }
            }, lyrics = {
                AnimatedContent(PlayerStage(song, translation.takeIf { showTranslation }, preparedLines, lyricsReady),
                    Modifier.fillMaxSize(), contentKey = { it.song.id }, transitionSpec = {
                        AmiMotion.track()
                    }, label = "player-lyrics") { stage ->
                    val stageLines = stage.lines ?: remember(stage.song.lyrics) { Lyrics.parse(stage.song.lyrics) }
                    val lastPosition = remember(stage.song.id) { longArrayOf(0L) }
                    val stageClock: () -> Long = {
                        if (stage.song.id == latestSongId) displayedPosition().also { lastPosition[0] = it } else lastPosition[0]
                    }
                    if (!stage.ready) Box(Modifier.fillMaxSize())
                    else if (stageLines.isNotEmpty()) LyricsStage(stage.song.id, stageLines, stage.translation, stageClock,
                        { if (stage.song.id == latestSongId && latestActions.canSeek) seek(it) }, Modifier.fillMaxSize(), seekEnabled=actions.canSeek,
                        seekSerial = if (stage.song.id == latestSongId) seekSerial else 0)
                    else EmptyLyrics()
                }
            }, controls = { lyricsMode, progress, thumbnail, toggle ->
                PlayerControls(song, playback, actions.copy(seek = seek), lyricsMode, progress, thumbnail, translating, translation != null,
                    showTranslation, onTranslation = { if (translation != null) showTranslation = !showTranslation else actions.translate() },
                    onLyrics = toggle, position = displayedPosition, modifier = Modifier.fillMaxWidth())
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
                }, transitionSpec = { AmiMotion.track() }, label = "player-track") { track ->
                    Column {
                        Text(track.first, fontSize = 22.sp, lineHeight = 28.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.second, Modifier.padding(top = 4.dp), fontSize = 13.sp, lineHeight = 19.sp,
                            color = Color.White.copy(alpha = .56f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!actions.external) ActionIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (song.favorite) "取消喜欢" else "加入喜欢", actions.favorite,
                    tint = if (song.favorite) FavoriteRed else Color.White.copy(alpha = .55f), enabled = actions.canFavorite)
            }
            val duration = playback.duration.takeIf { it > 0 } ?: song.duration
            PlayerTimeline(song.id, duration, playback.buffering, position, actions.seek, actions.canSeek)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides iconSize) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (actions.external) Spacer(Modifier.size(iconSize)) else ActionIcon(Icons.Rounded.Shuffle, if (playback.shuffle) "关闭随机播放" else "开启随机播放", actions.shuffle,
                        tint = Color.White.copy(alpha = if (playback.shuffle) 1f else .4f), modifier = Modifier.size(iconSize))
                    ActionIcon(Icons.Rounded.SkipPrevious, "上一首", actions.previous, tint=Color.White.copy(alpha=if(actions.canPrevious)1f else .25f), enabled=actions.canPrevious, modifier = Modifier.size(iconSize))
                    FilledIconButton(actions.toggle, Modifier.size(if (compact) 66.dp else 72.dp), shape = CircleShape, enabled=actions.canToggle) {
                        AnimatedContent(playback.playing, transitionSpec = {
                            (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .8f)) togetherWith
                                (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = .8f))
                        }, label = "transport-toggle") { playing ->
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(36.dp))
                        }
                    }
                    ActionIcon(Icons.Rounded.SkipNext, "下一首", actions.next, tint=Color.White.copy(alpha=if(actions.canNext)1f else .25f), enabled=actions.canNext, modifier = Modifier.size(iconSize))
                    if (actions.external) Spacer(Modifier.size(iconSize)) else ActionIcon(if (playback.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (playback.repeat) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" }, actions.repeat,
                        tint = Color.White.copy(alpha = if (playback.repeat > 0) 1f else .4f), modifier = Modifier.size(iconSize))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics,
                    if (showLyrics) "切换到封面" else "切换到歌词", onLyrics, tint = Color.White.copy(alpha = .85f))
                if (showLyrics && song.lyrics.isNotBlank()) ActionIcon(Icons.Rounded.Translate,
                    if (translated) if (showTranslation) "隐藏译文" else "显示译文" else "翻译歌词", onTranslation,
                    enabled = !translating, tint = Color.White.copy(alpha = if (translated && !showTranslation) .45f else .85f))
                else Spacer(Modifier.size(48.dp))
                ActionIcon(if (actions.external) Icons.Rounded.OpenInNew else Icons.Rounded.QueueMusic,
                    if (actions.external) "打开网易云音乐" else "播放队列", actions.queue, tint = Color.White.copy(alpha = .85f))
            }
        }
    }
}

private data class SeekPreview(val serial: Int, val position: Long)

/** Frame-clock reads stop here: artwork, buttons and the library never observe each tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerTimeline(songId: String, duration: Long, buffering: Boolean, position: () -> Long, onSeek: (Long) -> Unit, seekEnabled: Boolean = true) {
    var seeking by remember(songId) { mutableStateOf<Float?>(null) }
    val displayed = (seeking ?: position().toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat())
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .8f), inactiveTrackColor = Color.White.copy(alpha = .15f))
    Column {
        Slider(value = displayed,
            onValueChange = { seeking = it }, onValueChangeFinished = {
                seeking?.toLong()?.let(onSeek)
                seeking = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && seekEnabled, colors = colors,
            thumb = {
                Box(Modifier.width(8.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(Color.White.copy(alpha = if (duration > 0) 1f else .3f), CircleShape))
                }
            },
            track = { SliderDefaults.Track(it, Modifier.height(3.dp), enabled = duration > 0 && seekEnabled, colors = colors,
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
