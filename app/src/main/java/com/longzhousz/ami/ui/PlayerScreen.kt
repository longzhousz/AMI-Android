package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.LyricLine
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.isCloudSong
import com.longzhousz.ami.core.durationLabel
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.playback.PlayMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class PlayerActions(
    val close: () -> Unit = {}, val queue: () -> Unit = {}, val lyricFile: () -> Unit = {},
    val favorite: () -> Unit = {}, val seek: (Long) -> Unit = {}, val toggle: () -> Unit = {},
    val previous: () -> Unit = {}, val next: () -> Unit = {}, val mode: (PlayMode) -> Unit = {},
    val translate: () -> Unit = {}, val retranslate: () -> Unit = translate, val restoreLyrics: () -> Unit = {},
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
        mode = { mode -> model.act("player.mode") { model.player.mode(mode) } },
        translate = { model.translate(song) }, retranslate = { model.translate(song, force = true) },
        restoreLyrics = { model.clearLyrics(song) },
        canFavorite = !song.isCloudSong() || model.cloud.direct && likes.ready && song.neteaseId !in likes.pending,
    ), lyricPosition = { clock.value }, dragHandle = dragHandle, preparedLines = document?.lines, lyricsReady = document != null,
        audioQuality = if (song.isCloudSong()) qualities[song.id]?.label ?: "待确认" else song.format.ifBlank { "待确认" }, lyricsPage = lyricsPage)
}

internal val PlayerColors = darkColorScheme(
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
        GlassBackdropHost(Modifier.fillMaxSize().testTag("full-player"), background = { PlayerBackdrop(song.cover, dark = true) }) {
            PlayerArtworkScene(song, lyricsPage, header = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-header").then(dragHandle)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放页", actions.close)
                    Spacer(Modifier.weight(1f))
                    actions.settings?.let { ActionIcon(Icons.Rounded.Tune,"设置",it,tint=Color.White.copy(alpha=.7f)) }
                    Box {
                        ActionIcon(Icons.Rounded.MoreHoriz, "歌词选项", { lyricMenu = true }, tint = Color.White.copy(alpha = .7f))
                        GlassDropdownMenu(lyricMenu, { lyricMenu = false }) {
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
        val horizontal = if (maxWidth < 360.dp) 16.dp else 28.dp
        val compact = maxWidth - horizontal * 2 < 300.dp
        var modeMenu by remember { mutableStateOf(false) }
        val mode = PlayMode.from(playback.shuffle, playback.repeat)
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
                    tint = if (song.favorite) FavoriteRed else Color.White.copy(alpha = .55f), enabled = actions.canFavorite, glass = false)
            }
            val duration = playback.duration.takeIf { it > 0 } ?: song.duration
            PlayerTimeline(song.id, duration, playback.buffering, position, actions.seek, actions.canSeek)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp).testTag("player-transport"),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (actions.external) Spacer(Modifier.size(48.dp)) else Box {
                    TransportButton(mode.icon(), "播放模式：${mode.label}", { modeMenu = true }, tint = Color.White.copy(alpha = .65f))
                    GlassDropdownMenu(modeMenu, { modeMenu = false }) {
                        PlayMode.entries.forEach { option ->
                            DropdownMenuItem(text = {
                                Column {
                                    Text(option.label)
                                    if (option == PlayMode.Ordered || option == PlayMode.Loop) Text(
                                        if (option == PlayMode.Ordered) "播完队列后停止" else "播完队列后从头继续", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }, leadingIcon = { Icon(option.icon(), null) },
                                trailingIcon = { if (mode == option) Icon(Icons.Rounded.Check, "当前模式") },
                                onClick = { modeMenu = false; actions.mode(option) })
                        }
                    }
                }
                TransportButton(Icons.Rounded.SkipPrevious, "上一首", actions.previous, enabled = actions.canPrevious,
                    size = if (compact) 48.dp else 56.dp, iconSize = 34.dp)
                TransportButton(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playback.playing) "暂停" else "播放", actions.toggle, enabled = actions.canToggle,
                    primary = true, size = if (compact) 72.dp else 80.dp, iconSize = 40.dp)
                TransportButton(Icons.Rounded.SkipNext, "下一首", actions.next, enabled = actions.canNext,
                    size = if (compact) 48.dp else 56.dp, iconSize = 34.dp)
                TransportButton(if (actions.external) Icons.Rounded.OpenInNew else Icons.Rounded.QueueMusic,
                    if (actions.external) "打开网易云音乐" else "播放队列", actions.queue, tint = Color.White.copy(alpha = .65f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics,
                    if (showLyrics) "切换到封面" else "切换到歌词", onLyrics, tint = Color.White.copy(alpha = .65f), glass = false)
                ActionIcon(Icons.Rounded.Translate,
                    if (translated) if (showTranslation) "隐藏译文" else "显示译文" else "翻译歌词", onTranslation,
                    enabled = !translating && song.lyrics.isNotBlank(), tint = Color.White.copy(alpha = if (translated && !showTranslation) .35f else .65f), glass = false)
            }
        }
    }
}

private fun PlayMode.icon(): ImageVector = when (this) {
    PlayMode.Ordered -> Icons.Rounded.ArrowForward
    PlayMode.Loop -> Icons.Rounded.Repeat
    PlayMode.One -> Icons.Rounded.RepeatOne
    PlayMode.Shuffle -> Icons.Rounded.Shuffle
}

/** A single solid primary button; the four supporting controls have no individual frames. */
@Composable private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit,
    enabled: Boolean = true, primary: Boolean = false, size: Dp = 48.dp, iconSize: Dp = 24.dp,
    tint: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .92f else 1f, spring(dampingRatio = .75f, stiffness = 600f), label = "transport-press")
    val surface = if (primary) Modifier.shadow(14.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .14f), spotColor = Color.Black.copy(alpha = .20f))
        .background(Color.White.copy(alpha = if (enabled) .94f else .30f), CircleShape) else Modifier
    IconButton(onClick, Modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.then(surface),
        enabled = enabled, interactionSource = interaction) {
        AnimatedContent(icon to label, transitionSpec = {
            (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .85f)) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = .85f))
        }, label = "transport-icon") { target ->
            Icon(target.first, target.second, Modifier.size(iconSize),
                tint = (if (primary) Color(0xFF202431) else tint).copy(alpha = if (enabled) (if (primary) 1f else tint.alpha) else .28f))
        }
    }
}

private data class SeekPreview(val serial: Int, val position: Long)

/** Frame-clock reads stop here: artwork, buttons and the library never observe each tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerTimeline(songId: String, duration: Long, buffering: Boolean, position: () -> Long, onSeek: (Long) -> Unit, seekEnabled: Boolean = true) {
    var seeking by remember(songId) { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val thumbSize by animateDpAsState(if (dragged || pressed) 13.dp else 6.dp, spring(dampingRatio = .9f, stiffness = 500f), label = "seek-thumb")
    val trackSize by animateDpAsState(if (dragged || pressed) 5.dp else 3.dp, spring(dampingRatio = .9f, stiffness = 500f), label = "seek-track")
    val displayed = (seeking ?: position().toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat())
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .8f), inactiveTrackColor = Color.White.copy(alpha = .15f))
    Column {
        Slider(value = displayed,
            onValueChange = { seeking = it }, onValueChangeFinished = {
                seeking?.toLong()?.let(onSeek)
                seeking = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && seekEnabled, colors = colors, interactionSource = interaction,
            thumb = {
                Box(Modifier.width(16.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(thumbSize).background(Color.White.copy(alpha = if (duration > 0) 1f else .3f), CircleShape))
                }
            },
            track = { SliderDefaults.Track(it, Modifier.height(trackSize), enabled = duration > 0 && seekEnabled, colors = colors,
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
