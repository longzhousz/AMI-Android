package com.longzhousz.ami.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.platform.LocalContext
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

private data class ArtworkColors(val top: Color, val bottom: Color, val glow: Color)
private val FallbackColors = ArtworkColors(Color(0xFF41465F), Color(0xFF151923), Color(0xFF69617C))
private val ArtworkColorCache = LruCache<String, ArtworkColors>(32)
internal fun clearArtworkColorCache() { ArtworkColorCache.evictAll() }

/** Sample cached artwork once off the main thread; no full-resolution texture or perpetual animation. */
@Composable internal fun PlayerBackdrop(cover: String?) {
    val context = LocalContext.current
    val colors by produceState(FallbackColors, cover) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (cover == null) return@runCatching FallbackColors
                val remote = cover.startsWith("https://")
                val key = if (remote) cover else "$cover:${File(cover).lastModified()}"
                ArtworkColorCache.get(key)?.let { return@runCatching it }
                val bitmap = if (remote) {
                    val result = context.imageLoader.execute(ImageRequest.Builder(context).data(cover).size(48).allowHardware(false)
                        .build()) as? SuccessResult
                    (result?.drawable as? BitmapDrawable)?.bitmap
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cover, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 48) sample *= 2
                    BitmapFactory.decodeFile(cover, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching FallbackColors
                try {
                    var r = 0L; var g = 0L; var b = 0L; var count = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.alpha(pixel) > 128) {
                            r += android.graphics.Color.red(pixel); g += android.graphics.Color.green(pixel); b += android.graphics.Color.blue(pixel); count++
                        }
                    }
                    if (count == 0) return@runCatching FallbackColors
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV((r / count).toInt(), (g / count).toInt(), (b / count).toInt(), hsv)
                    fun tone(hue: Float, saturation: Float, brightness: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue % 360, saturation, brightness)))
                    val saturation = hsv[1].coerceIn(.16f, .45f)
                    ArtworkColors(tone(hsv[0], saturation, .31f), tone(hsv[0] + 15, saturation, .10f), tone(hsv[0] + 24, saturation * .8f, .43f))
                        .also { ArtworkColorCache.put(key, it) }
                } finally { if (!remote) bitmap.recycle() }
            }.getOrDefault(FallbackColors)
        }
    }
    val top = animateColorAsState(colors.top, tween(600), label = "artwork-top")
    val bottom = animateColorAsState(colors.bottom, tween(600), label = "artwork-bottom")
    val glow = animateColorAsState(colors.glow, tween(600), label = "artwork-glow")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top.value, bottom.value)))
        drawRect(Brush.radialGradient(listOf(glow.value.copy(alpha = .6f), Color.Transparent), center = Offset(size.width * .9f, size.height * .18f), radius = size.maxDimension * .7f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .22f))))
    }
}
