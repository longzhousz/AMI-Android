package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.durationLabel
import java.io.File

private val CoverPalette = listOf(Color(0xFF7489B8), Color(0xFF8C9A86), Color(0xFFB58C82), Color(0xFF8D86A6), Color(0xFF789D9E))
internal class ArtworkMemory {
    var expectedPath: String? = null
    var key by mutableStateOf<MemoryCache.Key?>(null)
}

@Composable internal fun Cover(path: String?, title: String, modifier: Modifier = Modifier, memory: ArtworkMemory? = null) {
    val context = LocalContext.current
    var previousImage by remember { mutableStateOf<MemoryCache.Key?>(null) }
    val latestPath by rememberUpdatedState(path)
    Crossfade(path to title, modifier.clip(RoundedCornerShape(18.dp)), animationSpec = tween(260), label = "artwork") { artwork ->
        val color = CoverPalette[(artwork.second.hashCode().toLong().let { if (it < 0) -it else it } % CoverPalette.size).toInt()]
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(color.copy(alpha = 0.65f), color))), contentAlignment = Alignment.Center) {
        if (artwork.first != null) {
            val request = remember(artwork.first, context) { ImageRequest.Builder(context).data(File(artwork.first!!))
                .placeholderMemoryCacheKey(memory?.key ?: previousImage).crossfade(150).build() }
            AsyncImage(request, "《${artwork.second}》封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onSuccess = { if (artwork.first == latestPath && (memory == null || memory.expectedPath == artwork.first)) {
                    if (memory == null) previousImage = it.result.memoryCacheKey else memory.key = it.result.memoryCacheKey
                } })
        }
        else Canvas(Modifier.fillMaxSize().padding(18.dp).semantics { contentDescription = "《${artwork.second}》占位封面" }) {
            val radius = size.minDimension * .43f
            drawCircle(Color.White.copy(alpha = .17f), radius)
            drawCircle(Color.White.copy(alpha = .13f), radius * .72f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * .02f))
            drawCircle(Color.White.copy(alpha = .14f), radius * .55f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * .02f))
            drawCircle(Color.White.copy(alpha = .6f), radius * .2f)
            drawCircle(color, radius * .055f)
            drawLine(Color.White.copy(alpha = .2f), Offset(size.width * .2f, size.height * .8f), Offset(size.width * .8f, size.height * .2f), radius * .025f)
        }
        }
    }
}

@Composable fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .86f else 1f, spring(dampingRatio = .72f, stiffness = 700f), label = "icon-press")
    val color by animateColorAsState(if (enabled) tint else tint.copy(alpha = .38f), tween(180), label = "icon-tint")
    IconButton(onClick, modifier.size(48.dp), enabled = enabled, interactionSource = interaction) {
        AnimatedContent(icon to label, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, transitionSpec = {
            (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .8f)) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = .8f))
        }, label = "action-icon") { target -> Icon(target.first, target.second, tint = color) }
    }
}

@Composable fun SongRow(song: Song, playing: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onRemove: (() -> Unit)? = null, number: Int? = null, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent, tween(220), label = "track-highlight")
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background).clickable(onClick = onPlay).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Box(Modifier.width(35.dp), contentAlignment = Alignment.Center) {
            if (playing) Icon(Icons.Rounded.Equalizer, "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            else Text("%02d".format(number), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else { Cover(song.cover, song.album, Modifier.size(50.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(durationLabel(song.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            ActionIcon(Icons.Rounded.MoreVert, "${song.title}的更多操作", { menu = true })
            DropdownMenu(menu, { menu = false }) {
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
        Box(Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (trailing != null) trailing() else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
