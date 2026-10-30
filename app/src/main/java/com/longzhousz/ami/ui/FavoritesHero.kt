package com.longzhousz.ami.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longzhousz.ami.core.CloudTrack

/** A single orderly mosaic, fading into the page. Only the already-loaded prefix is requested. */
@Composable internal fun FavoritesHero(tracks: List<CloudTrack>) {
    val base = MaterialTheme.colorScheme.background
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("favorites-hero")) {
        val width = maxWidth
        val height = (width * .9f + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()).coerceIn(330.dp, 420.dp)
        Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
            val columns = when { tracks.size > 18 -> 6; tracks.size > 9 -> 5; tracks.size > 3 -> 4; else -> tracks.size.coerceAtLeast(1) }
            val edge = width / columns * 1.18f
            Column(Modifier.align(Alignment.TopCenter).offset(y = (-24).dp)
                .requiredWidth(edge * columns).graphicsLayer { rotationZ = -9f }
                .testTag("favorites-album-wall").clearAndSetSemantics { contentDescription = "最近喜欢的 ${tracks.size} 首歌曲封面" },
                horizontalAlignment = Alignment.CenterHorizontally) {
                tracks.chunked(columns).forEach { row ->
                    Row {
                        row.forEach { track -> Cover(track.cover, track.title, Modifier.size(edge).testTag("favorite-wall-${track.id}")) }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = .08f))
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .42f), .4f to Color.Transparent,
                    .64f to base.copy(alpha = .30f), .9f to base.copy(alpha = .95f), 1f to base))
            }
            Text("我喜欢", Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 26.dp),
                fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
