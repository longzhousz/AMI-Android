package com.longzhousz.ami.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

internal val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalVisualsActive = compositionLocalOf { true }
internal val ColorScheme.isDark: Boolean get() = surface.luminance() < .5f

/** Only the background is captured here. Glass children never sample their own output. */
@Composable internal fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    background: @Composable () -> Unit = { GlassCanvas() },
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop)) { background() }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

@Composable internal fun GlassCanvas() {
    val dark = MaterialTheme.colorScheme.isDark
    val base = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        drawRect(Brush.radialGradient(listOf(Color(0xFF7A9EFF).copy(alpha = if (dark) .16f else .12f), Color.Transparent),
            center = Offset(size.width * .08f, size.height * .12f), radius = size.maxDimension * .7f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFC49DBD).copy(alpha = if (dark) .13f else .10f), Color.Transparent),
            center = Offset(size.width, size.height * .62f), radius = size.maxDimension * .6f))
    }
}

/** Kyant Backdrop's GPU optics: blur, lens refraction, edge highlight and soft shadow. */
@Composable internal fun Modifier.liquidGlass(
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    prominent: Boolean = false,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
    val dark = MaterialTheme.colorScheme.isDark
    val tint = if (dark) Color(0xFF202633).copy(alpha = if (prominent) .48f else .34f)
        else Color.White.copy(alpha = if (prominent) .60f else .42f)
    // Standalone previews/tests may not have a source; the application always supplies one.
    if (backdrop == null) return this.then(Modifier.drawBehindGlassFallback(shape, tint))
    return drawBackdrop(backdrop, shape = { shape }, effects = {
        vibrancy()
        blur(if (prominent) 10.dp.toPx() else 6.dp.toPx())
        lens(12.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = prominent)
    }, highlight = { Highlight(width = .7.dp, alpha = if (dark) .65f else .85f) },
        shadow = { Shadow(radius = if (prominent) 20.dp else 10.dp, color = Color.Black.copy(alpha = if (dark) .19f else .08f)) },
        onDrawSurface = { drawRect(tint) })
}

private fun Modifier.drawBehindGlassFallback(shape: CornerBasedShape, tint: Color): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawOutline(outline, tint) }
    }

@Composable internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    prominent: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier.liquidGlass(shape, prominent), content = content)
    }
}

@Composable internal fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(onDismissRequest, confirmButton, modifier.liquidGlass(RoundedCornerShape(28.dp), prominent = true),
        dismissButton = dismissButton, icon = icon, title = title, text = text, containerColor = Color.Transparent)
}

@Composable internal fun GlassSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(data, Modifier.padding(12.dp).liquidGlass(RoundedCornerShape(22.dp), prominent = true),
            containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun GlassDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded, onDismissRequest, modifier.liquidGlass(RoundedCornerShape(24.dp), prominent = true),
        shape = RoundedCornerShape(24.dp), containerColor = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp, content = content)
}

@Composable internal fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.liquidGlass(CircleShape, prominent = true), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary), content = content)
}
