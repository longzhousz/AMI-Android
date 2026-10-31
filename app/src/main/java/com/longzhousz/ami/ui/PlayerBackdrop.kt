package com.longzhousz.ami.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import android.content.Context
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.platform.LocalContext
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.CachePolicy
import kotlin.math.cos
import kotlin.math.sin

private data class ArtworkColors(val top: Color, val bottom: Color, val glow: Color, val accent: Color)
private val FallbackColors = ArtworkColors(Color(0xFF303A62), Color(0xFF111523), Color(0xFF7186C1), Color(0xFFB279A5))
private val ArtworkColorCache = LruCache<String, ArtworkColors>(32)
internal fun clearArtworkColorCache() { ArtworkColorCache.evictAll() }

/** Only the imminent neighbor, cancelled when playback/UI stops. Uses the existing bounded caches. */
internal suspend fun preparePlayerArtwork(context: Context, cover: String?) {
    if (cover == null) return
    val remote = cover.startsWith("https://")
    context.imageLoader.execute(ImageRequest.Builder(context).data(if (remote) cover else File(cover))
        .size(context.resources.displayMetrics.widthPixels.coerceIn(512, 1280))
        .apply { if (remote) memoryCacheKey(cover) }.build())
    currentCoroutineContext().ensureActive()
    artworkColors(context, cover)
}

private suspend fun artworkColors(context: Context, cover: String?): ArtworkColors = withContext(Dispatchers.IO) {
    try {
        if (cover == null) return@withContext FallbackColors
        val remote = cover.startsWith("https://")
        val key = if (remote) cover else "$cover:${File(cover).lastModified()}"
        ArtworkColorCache.get(key)?.let { return@withContext it }
        val bitmap = if (remote) {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(cover).size(48).allowHardware(false)
                // Do not replace the prewarmed full cover's memory entry with a 48 px sample.
                .memoryCachePolicy(CachePolicy.DISABLED).build()) as? SuccessResult
            (result?.drawable as? BitmapDrawable)?.bitmap
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cover, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 48) sample *= 2
            BitmapFactory.decodeFile(cover, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext FallbackColors
        try {
            val regions = Array(4) { LongArray(4) }
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 128) {
                    val region = regions[(if (y >= bitmap.height / 2) 2 else 0) + if (x >= bitmap.width / 2) 1 else 0]
                    region[0] += android.graphics.Color.red(pixel); region[1] += android.graphics.Color.green(pixel)
                    region[2] += android.graphics.Color.blue(pixel); region[3]++
                }
            }
            val samples = regions.filter { it[3] > 0 }.map { region -> FloatArray(3).also {
                android.graphics.Color.RGBToHSV((region[0] / region[3]).toInt(), (region[1] / region[3]).toInt(), (region[2] / region[3]).toInt(), it)
            } }
            if (samples.isEmpty()) return@withContext FallbackColors
            val vivid = samples.maxBy { it[1] * it[2] }
            val alternate = samples.maxBy { minOf(kotlin.math.abs(it[0] - vivid[0]), 360 - kotlin.math.abs(it[0] - vivid[0])) * it[1] }
            fun tone(hsv: FloatArray, brightness: Float) = Color(android.graphics.Color.HSVToColor(
                floatArrayOf(hsv[0], hsv[1].coerceIn(.22f, .72f), brightness)))
            ArtworkColors(tone(vivid, .24f), tone(alternate, .09f), tone(vivid, .72f), tone(alternate, .65f))
                .also { ArtworkColorCache.put(key, it) }
        } finally { if (!remote) bitmap.recycle() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { FallbackColors }
}

/** A 48 px sample shares the artwork cache. Motion only invalidates drawing while visible/resumed. */
@Composable internal fun PlayerBackdrop(cover: String?, dark: Boolean = MaterialTheme.colorScheme.isDark) {
    val context = LocalContext.current
    // A prewarmed remote palette is available in the very same frame as the new cover/title.
    val cached = remember(cover) { cover?.takeIf { it.startsWith("https://") }?.let(ArtworkColorCache::get) }
    val loaded by produceState(cached ?: FallbackColors, cover) {
        value = artworkColors(context, cover)
    }
    val transition = updateTransition(cached ?: loaded, label = "artwork-palette")
    val top = transition.animateColor({ tween(AmiMotion.TRACK_MILLIS) }, label = "artwork-top") { it.top }
    val bottom = transition.animateColor({ tween(AmiMotion.TRACK_MILLIS) }, label = "artwork-bottom") { it.bottom }
    val glow = transition.animateColor({ tween(AmiMotion.TRACK_MILLIS) }, label = "artwork-glow") { it.glow }
    val accent = transition.animateColor({ tween(AmiMotion.TRACK_MILLIS) }, label = "artwork-accent") { it.accent }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = LocalVisualsActive.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val lastFrame = remember { floatArrayOf(0f) }
    val startPhase = remember(active) { lastFrame[0] }
    val phase = if (active) rememberInfiniteTransition(label = "artwork-aurora").animateFloat(
        initialValue = startPhase, targetValue = startPhase + (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart), label = "aurora-phase")
        else remember(active) { mutableFloatStateOf(startPhase) }
    Canvas(Modifier.fillMaxSize()) {
        val t = phase.value
        // Not snapshot state: retain the last rendered angle without scheduling a recomposition.
        lastFrame[0] = t % (Math.PI * 2).toFloat()
        drawRect(Brush.verticalGradient(if (dark) listOf(top.value, bottom.value)
            else listOf(lerp(Color.White, glow.value, .10f), lerp(Color(0xFFF7F8FC), accent.value, .07f))))
        fun halo(color: Color, x: Float, y: Float, radius: Float, alpha: Float) {
            drawRect(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .35f), Color.Transparent),
                center = Offset(size.width * x, size.height * y), radius = size.maxDimension * radius))
        }
        halo(glow.value, .38f + .36f * sin(t), .32f + .24f * cos(t), .64f, if (dark) .62f else .24f)
        halo(accent.value, .68f + .30f * cos(t), .65f + .22f * sin(t), .57f, if (dark) .55f else .20f)
        halo(glow.value, .5f + .40f * cos(t * 2), .85f + .16f * sin(t * 2), .44f, if (dark) .23f else .12f)
        if (dark) drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .05f), Color.Black.copy(alpha = .28f))))
    }
}
