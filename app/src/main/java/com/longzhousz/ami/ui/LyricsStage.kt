package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
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

/** A bounded viewport: rows keep their natural height, including long bilingual lyrics. */
@Composable internal fun LyricsStage(
    songId: String,
    lines: List<LyricLine>,
    translation: List<String>?,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
    seekSerial: Int = 0,
) {
    val list = rememberLazyListState()
    val clock by rememberUpdatedState(position)
    val active by remember(lines) { derivedStateOf { Lyrics.active(lines, clock()) } }
    val synced = remember(lines) { lines.any { it.time != null } }
    var following by rememberSaveable(songId, lines) { mutableStateOf(true) }
    var initiallyPositioned by remember(songId, lines) { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val relocation = remember(songId) { Animatable(1f) }
    LaunchedEffect(seekSerial) { if (seekSerial > 0) following = true }
    // Programmatic scrolling must never look like a user gesture or resume following by itself.
    LaunchedEffect(dragging) { if (dragging && synced) following = false }

    BoxWithConstraints(modifier.testTag("lyrics-stage")) {
        val density = LocalDensity.current
        val anchor = maxHeight * .28f
        val viewport = with(density) { maxHeight.toPx() }
        val fontSize = when { maxWidth < 360.dp -> 28.sp; maxWidth > 600.dp -> 36.sp; else -> 32.sp }
        LaunchedEffect(songId, lines, active, following, viewport, translation, dragging) {
            if (!following || dragging || !synced) { relocation.snapTo(1f); return@LaunchedEffect }
            // BoxWithConstraints subcomposes during measurement. Wait for the next frame before
            // scrollToItem can force another measure pass (Compose 1.10 / Android 17).
            withFrameNanos { }
            val target = active.coerceAtLeast(0)
            val visible = list.layoutInfo.visibleItemsInfo.any { it.index == target }
            val jump = !initiallyPositioned || !visible
            if (initiallyPositioned && !visible) relocation.animateTo(.25f, tween(80))
            if (jump) {
                // A seek across verses should not fly through every intervening paragraph.
                list.scrollToItem(target)
                initiallyPositioned = true
            }
            // Lazy-list offsets exclude the leading padding. Keep the current paragraph at a
            // quiet reading anchor; lift exceptionally tall paragraphs so their last line fits.
            withFrameNanos { }
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.let { row ->
                val padding = list.layoutInfo.beforeContentPadding
                val desired = minOf(padding.toFloat(), ((viewport - row.size) * .5f).coerceAtLeast(16f * density.density))
                val distance = row.offset + padding - desired
                if (abs(distance) > 1) {
                    if (jump) list.scrollToItem(target, (padding - desired).toInt())
                    else list.animateScrollBy(distance, AmiMotion.lyric)
                }
            }
            relocation.animateTo(1f, tween(180))
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("lyrics-list")
                .graphicsLayer { alpha = relocation.value; compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .10f to Color.Black, .86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * .75f, start = 30.dp, end = 30.dp),
        ) {
            itemsIndexed(lines, key = { index, line -> "$songId:$index:${line.time}" }, contentType = { _, _ -> "lyric" }) { index, line ->
                val current = active >= 0 && line.time != null && line.time == lines[active].time
                val opacity = animateFloatAsState(
                    if (!synced || !following) .88f else if (current) 1f else if (index < active) .30f else .44f,
                    tween(300), label = "lyric-opacity",
                )
                val scale = animateFloatAsState(if (current || !following || !synced) 1f else .975f,
                    androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f), label = "lyric-scale")
                Column(
                    Modifier.fillMaxWidth().testTag("lyric-$index")
                        .semantics { selected = current; if (current) stateDescription = "当前歌词" }
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = line.time != null && seekEnabled, onClickLabel = "跳转到这句歌词") {
                            line.time?.let { following = true; onSeek(it) }
                        }
                        .padding(vertical = 15.dp)
                        .graphicsLayer { alpha = opacity.value; scaleX = scale.value; scaleY = scale.value; transformOrigin = TransformOrigin(0f, .5f) },
                ) {
                    WordLyricText(line, current && following && line.words.isNotEmpty(), clock, style = TextStyle(
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
        AnimatedVisibility(!following && synced, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 3 }) {
            FilledTonalButton(
                { following = true },
                modifier = Modifier.liquidGlass(androidx.compose.foundation.shape.CircleShape),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp)); Text("回到当前句")
            }
        }
        if (!synced) Text("暂无时间轴 · 可自由滚动阅读", Modifier.align(Alignment.BottomCenter).padding(12.dp),
            color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
    }
}
