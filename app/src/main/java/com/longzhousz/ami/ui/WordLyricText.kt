package com.longzhousz.ami.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.longzhousz.ami.core.LyricLine

/** The existing visible clock invalidates drawing only; words never remeasure every frame. */
@Composable internal fun WordLyricText(line: LyricLine, enabled: Boolean, position: () -> Long, style: TextStyle) {
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    val paths = remember(line, layout, enabled) {
        val measured = layout
        if (!enabled || measured == null || measured.size.height > 8192) emptyList()
        else line.words.map { it to measured.getPathForRange(it.from, it.until) }
    }
    val mask = remember { Paint().apply { blendMode = BlendMode.DstIn } }
    val modifier = if (paths.isEmpty()) Modifier else Modifier.testTag("word-timed-lyric")
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val now = position()
            // A single alpha mask preserves antialiasing, wrapping and bidirectional shaping.
            // Only the active original line uses this layer; translations remain ordinary text.
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), mask)
            drawRect(Color.White.copy(alpha = .36f))
            paths.forEach { (word, path) ->
                val progress = if (word.end == word.start) { if (now >= word.start) 1f else 0f }
                    else ((now - word.start).toDouble() / (word.end - word.start)).toFloat().coerceIn(0f, 1f)
                if (progress > 0f) drawPath(path, Color.White.copy(alpha = .36f + .64f * progress), blendMode = BlendMode.Src)
            }
            drawContext.canvas.restore()
        }
    Text(line.text, modifier, color = Color(0xFFFFFCF8), style = style, onTextLayout = { if (line.words.isNotEmpty()) layout = it })
}
