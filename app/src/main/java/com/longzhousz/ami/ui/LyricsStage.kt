package com.longzhousz.ami.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longzhousz.ami.core.LyricLine
import com.longzhousz.ami.core.Lyrics
import kotlin.math.abs

private val LyricEase = CubicBezierEasing(.22f, .8f, .2f, 1f)

/** A bounded viewport: rows keep their natural height, including long bilingual lyrics. */
@Composable internal fun LyricsStage(
    songId: String,
    lines: List<LyricLine>,
    translation: List<String>?,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val active by remember(lines, position) { derivedStateOf { Lyrics.active(lines, position()) } }
    val synced = remember(lines) { lines.any { it.time != null } }
    var following by rememberSaveable(songId, lines) { mutableStateOf(true) }
    var initiallyPositioned by remember(songId, lines) { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    // Programmatic scrolling must never look like a user gesture or resume following by itself.
    LaunchedEffect(dragging) { if (dragging && synced) following = false }

    BoxWithConstraints(modifier.testTag("lyrics-stage")) {
        val density = LocalDensity.current
        val anchor = maxHeight * .28f
        val viewport = with(density) { maxHeight.toPx() }
        val fontSize = when { maxWidth < 360.dp -> 28.sp; maxWidth > 600.dp -> 36.sp; else -> 32.sp }
        LaunchedEffect(songId, lines, active, following, viewport, translation) {
            if (!following || dragging || !synced) return@LaunchedEffect
            val target = active.coerceAtLeast(0)
            if (!initiallyPositioned) {
                list.scrollToItem(target)
                initiallyPositioned = true
            } else {
                val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (row == null) list.animateScrollToItem(target)
            }
            // Lazy-list offsets exclude the leading padding. Keep the current paragraph at a
            // quiet reading anchor; lift exceptionally tall paragraphs so their last line fits.
            withFrameNanos { }
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.let { row ->
                val padding = list.layoutInfo.beforeContentPadding
                val desired = minOf(padding.toFloat(), ((viewport - row.size) * .5f).coerceAtLeast(16f * density.density))
                val distance = row.offset + padding - desired
                if (abs(distance) > 1) list.animateScrollBy(distance, tween(920, easing = LyricEase))
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("lyrics-list")
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .10f to Color.Black, .86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * .75f, start = 30.dp, end = 30.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val current = active >= 0 && line.time != null && line.time == lines[active].time
                val distance = if (active < 0) index + 1 else abs(index - active)
                val opacity by animateFloatAsState(
                    if (!synced || !following) .88f else if (current) 1f else if (index < active) .30f else .44f,
                    tween(600), label = "lyric-opacity",
                )
                val scale by animateFloatAsState(if (current || !following || !synced) 1f else .975f, tween(800, easing = LyricEase), label = "lyric-scale")
                // Soft depth on modern devices, without blurring the paragraph being read.
                // Manual browsing and large accessibility fonts always retain crisp text.
                val soft = Build.VERSION.SDK_INT >= 31 && synced && following && !current && distance > 1 && density.fontScale < 1.4f
                Column(
                    Modifier.fillMaxWidth().testTag("lyric-$index")
                        .semantics { selected = current; if (current) stateDescription = "当前歌词" }
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = line.time != null, onClickLabel = "跳转到这句歌词") {
                            line.time?.let { following = true; onSeek(it) }
                        }
                        .padding(vertical = 15.dp)
                        .graphicsLayer { alpha = opacity; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) }
                        .then(if (soft) Modifier.blur(1.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
                ) {
                    Text(line.text, color = Color(0xFFFFFCF8), style = TextStyle(
                        fontSize = fontSize, lineHeight = fontSize * 1.28f, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.45).sp, lineBreak = LineBreak.Paragraph,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ))
                    translation?.getOrNull(index)?.takeIf { it.isNotBlank() && it != line.text }?.let {
                        Text(it, Modifier.padding(top = 9.dp), color = Color.White.copy(alpha = .72f),
                            style = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium,
                                lineBreak = LineBreak.Paragraph, platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    }
                }
            }
        }
        if (!following && synced) {
            FilledTonalButton(
                { following = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF393944), contentColor = Color.White),
                elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 4.dp),
            ) {
                Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp)); Text("回到当前句")
            }
        }
        if (!synced) Text("暂无时间轴 · 可自由滚动阅读", Modifier.align(Alignment.BottomCenter).padding(12.dp),
            color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
    }
}
