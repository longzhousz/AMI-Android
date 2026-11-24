package com.longzhousz.ami.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
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

@Stable
internal class GlassLayer(
    private val layer: LayerBackdrop,
    private val popupBackground: GlassLayer?,
) : Backdrop by layer {
    private var sourceCoordinates by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())

    fun capture(modifier: Modifier): Modifier = modifier.layerBackdrop(layer).onGloballyPositioned { sourceCoordinates = it }

    // A native DropdownMenu starts a new window. Align its sample to the source on screen.
    val inPopup: Backdrop =
        object : Backdrop {
            override val isCoordinatesDependent = true

            override fun DrawScope.drawBackdrop(
                density: Density,
                coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?,
            ) {
                if (coordinates?.isAttached != true) return
                val screen = coordinates.localToScreen(Offset.Zero)
                if (!screen.isSpecified) return
                with(this@GlassLayer) { drawAt(screen) }
            }
        }

    private fun DrawScope.drawAt(screen: Offset) {
        popupBackground?.let { with(it) { drawAt(screen) } }
        val source = sourceCoordinates?.takeIf { it.isAttached } ?: return
        val offset = source.screenToLocal(screen)
        if (offset.isSpecified) translate(-offset.x, -offset.y) { drawLayer(layer.graphicsLayer) }
    }
}

@Composable internal fun rememberGlassLayer(popupBackground: GlassLayer? = null): GlassLayer {
    val layer = rememberLayerBackdrop()
    return remember(layer, popupBackground) { GlassLayer(layer, popupBackground) }
}

/** Only the background is captured here. Glass children never sample their own output. */
@Composable internal fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit = { GlassCanvas() },
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberGlassLayer()
    Box(modifier) {
        Box(backdrop.capture(Modifier.matchParentSize())) { background() }
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) { content() }
    }
}

@Composable internal fun GlassCanvas() {
    val dark = MaterialTheme.colorScheme.isDark
    val base = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        if (!dark) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF7A9EFF).copy(alpha = .12f), Color.Transparent),
                    center = Offset(size.width * .08f, size.height * .12f),
                    radius = size.maxDimension * .7f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFFC49DBD).copy(alpha = .10f), Color.Transparent),
                    center = Offset(size.width, size.height * .62f),
                    radius = size.maxDimension * .6f,
                ),
            )
        }
    }
}

/** Kyant Backdrop's GPU optics: blur, lens refraction, edge highlight and soft shadow. */
@Composable internal fun Modifier.liquidGlass(
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    prominent: Boolean = false,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    forMenu: Boolean = false,
): Modifier {
    val dark = MaterialTheme.colorScheme.isDark
    val tintAlpha =
        when {
            forMenu -> if (dark) .74f else .68f
            prominent -> if (dark) .48f else .60f
            else -> if (dark) .34f else .42f
        }
    val tint = (if (dark) Color(0xFF202633) else Color.White).copy(alpha = tintAlpha)
    val blurRadius =
        when {
            forMenu -> 18.dp
            prominent -> 10.dp
            else -> 6.dp
        }
    // Standalone previews/tests may not have a source; the application always supplies one.
    if (backdrop == null) return this.then(Modifier.drawBehindGlassFallback(shape, tint))
    return drawBackdrop(
        backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(12.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = prominent)
        },
        highlight = { Highlight(width = .7.dp, alpha = if (dark) .65f else .85f) },
        shadow = { Shadow(radius = if (prominent) 20.dp else 10.dp, color = Color.Black.copy(alpha = if (dark) .19f else .08f)) },
        onDrawSurface = { drawRect(tint) },
    )
}

private fun Modifier.drawBehindGlassFallback(
    shape: CornerBasedShape,
    tint: Color,
): Modifier =
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
    AlertDialog(
        onDismissRequest,
        confirmButton,
        modifier.liquidGlass(RoundedCornerShape(28.dp), prominent = true),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        containerColor = Color.Transparent,
    )
}

@Composable internal fun GlassSnackbarHost(
    state: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // Material3's Snackbar draws its own Surface inside the glass, which shows a second
    // rounded rectangle around the text. Compose the message directly in one glass surface.
    SnackbarHost(state, modifier) { data ->
        GlassSurface(
            Modifier.padding(12.dp),
            shape = RoundedCornerShape(22.dp),
            prominent = true,
        ) {
            Row(
                Modifier.padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    data.visuals.message,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton({ data.performAction() }) { Text(label, color = MaterialTheme.colorScheme.primary) }
                }
                if (data.visuals.withDismissAction) {
                    IconButton({ data.dismiss() }) {
                        Icon(Icons.Rounded.Close, "关闭提示", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val source = LocalGlassBackdrop.current
    val backdrop = (source as? GlassLayer)?.inPopup ?: source
    DropdownMenu(
        expanded,
        onDismissRequest,
        modifier.liquidGlass(shape, prominent = true, backdrop = backdrop, forMenu = true),
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick,
        modifier.liquidGlass(CircleShape, prominent = true),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = contentPadding,
        content = content,
    )
}
