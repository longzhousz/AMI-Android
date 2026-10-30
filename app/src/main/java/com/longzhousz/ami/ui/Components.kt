package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.imageLoader
import coil.request.ImageRequest
import com.longzhousz.ami.core.isCloudSong
import androidx.compose.ui.platform.LocalContext
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.durationLabel
import java.io.File

private val CoverPalette = listOf(Color(0xFF7489B8), Color(0xFF8C9A86), Color(0xFFB58C82), Color(0xFF8D86A6), Color(0xFF789D9E))
@Composable internal fun Cover(path: String?, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var previousImage by remember { mutableStateOf<MemoryCache.Key?>(null) }
    var previousPainter by remember { mutableStateOf<Painter?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    val latestPath by rememberUpdatedState(path)
    val cachedPainter = remember(path, context) {
        path?.takeIf { it.startsWith("https://") }?.let { url ->
            (context.imageLoader.memoryCache?.get(MemoryCache.Key(url))?.bitmap
                ?: (context.applicationContext as? com.longzhousz.ami.AmiApplication)?.artwork?.preview(url))
                ?.let { BitmapPainter(it.asImageBitmap()) }
        }
    }
    val color = CoverPalette[(title.hashCode().toLong().let { if (it < 0) -it else it } % CoverPalette.size).toInt()]
    val fallback = path == null || failed
    val placeholder = if (fallback) Modifier.background(Brush.linearGradient(listOf(color.copy(alpha = 0.65f), color))) else Modifier
    Box(modifier.then(placeholder), contentAlignment = Alignment.Center) {
        if (fallback) Canvas(Modifier.fillMaxSize().padding(18.dp).semantics { contentDescription = "《${title}》占位封面" }) {
            val radius = size.minDimension * .43f
            drawCircle(Color.White.copy(alpha = .17f), radius)
            drawCircle(Color.White.copy(alpha = .13f), radius * .72f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * .02f))
            drawCircle(Color.White.copy(alpha = .14f), radius * .55f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * .02f))
            drawCircle(Color.White.copy(alpha = .6f), radius * .2f)
            drawCircle(color, radius * .055f)
            drawLine(Color.White.copy(alpha = .2f), Offset(size.width * .2f, size.height * .8f), Offset(size.width * .8f, size.height * .2f), radius * .025f)
        }
        if (path != null) {
            // Draw synchronously on re-entry, even before AsyncImage's first request frame.
            // A tiny preview covers a full-bitmap eviction without holding the whole shelf in RAM.
            val retained = previousPainter ?: cachedPainter
            if (retained != null && !failed) Image(retained, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            // One request and one retained painter. Fade only after decoding succeeds, so a slow
            // request cannot fade the old image into an empty frame; memory hits remain immediate.
            val request = remember(path, context) { ImageRequest.Builder(context).data(if (path.startsWith("https://")) path else File(path))
                .apply { if (path.startsWith("https://")) memoryCacheKey(path) }
                .placeholderMemoryCacheKey(previousImage ?: path.takeIf { it.startsWith("https://") }?.let { MemoryCache.Key(it) })
                .apply { if (previousPainter != null) crossfade(AmiMotion.TRACK_MILLIS) else crossfade(false) }.build() }
            // A new painter starts independently; cancellation of a slow old request must not
            // block the current cover. The retained placeholder stays outside this keyed scope.
            key(path) {
                AsyncImage(request, "《${title}》封面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    placeholder = retained,
                    onSuccess = { if (path == latestPath) { previousImage = it.result.memoryCacheKey; previousPainter = it.painter; failed = false } },
                    onError = { if (path == latestPath) failed = true })
            }
        }
    }
}

@Composable fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, modifier: Modifier = Modifier, glass: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .86f else 1f, spring(dampingRatio = .72f, stiffness = 700f), label = "icon-press")
    val color by animateColorAsState(if (enabled) tint else tint.copy(alpha = .38f), tween(180), label = "icon-tint")
    IconButton(onClick, modifier.size(48.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .then(if (glass) Modifier.liquidGlass(CircleShape) else Modifier), enabled = enabled, interactionSource = interaction) {
        AnimatedContent(icon to label, transitionSpec = {
            (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .8f)) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = .8f))
        }, label = "action-icon") { target -> Icon(target.first, target.second, tint = color) }
    }
}

internal val FavoriteRed = Color(0xFFEE4865)

@Composable fun SongRow(song: Song, playing: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onRemove: (() -> Unit)? = null, number: Int? = null, modifier: Modifier = Modifier, favoriteEnabled: Boolean = true, showFavorite: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent, tween(220), label = "track-highlight")
    Row(modifier.fillMaxWidth().background(background).clickable(onClick = onPlay).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Box(Modifier.width(35.dp), contentAlignment = Alignment.Center) {
            if (playing) Icon(Icons.Rounded.Equalizer, "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            else Text("%02d".format(number), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else { Cover(song.cover, song.album, Modifier.size(50.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(durationLabel(song.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (song.isCloudSong() && showFavorite) ActionIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            "${song.title}：${if (song.favorite) "取消喜欢" else "加入喜欢"}", onFavorite,
            tint = if (song.favorite) FavoriteRed else MaterialTheme.colorScheme.onSurfaceVariant, enabled = favoriteEnabled, glass = false)
        if (!song.isCloudSong()) Box {
            ActionIcon(Icons.Rounded.MoreVert, "${song.title}的更多操作", { menu = true }, glass = false)
            GlassDropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(if (song.favorite) { { Text("取消喜欢") } } else { { Text("加入喜欢") } }, { menu = false; onFavorite() }, leadingIcon = { Icon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) })
                if (onRemove != null) DropdownMenuItem({ Text("从曲库移除") }, { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, null) })
            }
        }
    }
}

@Composable fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).liquidGlass(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (trailing != null) trailing() else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
