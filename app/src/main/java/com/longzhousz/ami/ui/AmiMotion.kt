package com.longzhousz.ami.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal object AmiMotion {
    val sheet = spring<Float>(dampingRatio = .92f, stiffness = 380f)
    val lyric = spring<Float>(dampingRatio = 1f, stiffness = 170f)
    const val TRACK_MILLIS = 360

    fun track(): ContentTransform = (fadeIn(tween(280, 40)) +
        slideInVertically(tween(TRACK_MILLIS, easing = FastOutSlowInEasing)) { it / 18 }) togetherWith fadeOut(tween(180))

    fun page(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return (fadeIn(tween(200, 40)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 12 * direction }) togetherWith
            (fadeOut(tween(140)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 18 * direction })
    }
}

private enum class SheetAnchor { Expanded, Collapsed }

/** One window, one gesture owner, and interruptible springs for entry, release and dismissal. */
@Composable
internal fun PlayerSheet(
    collapsedBounds: Rect?,
    onDismiss: () -> Unit,
    collapsedContent: @Composable () -> Unit = {},
    onSystemBars: (statusCovered: Boolean, navigationCovered: Boolean) -> Unit = { _, _ -> },
    content: @Composable (close: () -> Unit, dragHandle: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val dismissed by rememberUpdatedState(onDismiss)
    val updateBars by rememberUpdatedState(onSystemBars)
    val finishDismiss = remember {
        var finished = false
        { if (!finished) { finished = true; dismissed() } }
    }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("player-sheet-host")) {
        val heightPx = with(density) { maxHeight.toPx() }
        val origin = collapsedBounds?.takeIf { it.top > 0f && it.top < heightPx }
        val travel = (origin?.top ?: heightPx).coerceAtLeast(1f)
        val anchors = remember(travel) {
            DraggableAnchors {
                SheetAnchor.Expanded at 0f
                SheetAnchor.Collapsed at travel
            }
        }
        val state = remember {
            AnchoredDraggableState(SheetAnchor.Collapsed).apply {
                updateAnchors(anchors)
            }
        }
        // Insets/keyboard and window size changes must not recreate an open or held sheet.
        SideEffect { state.updateAnchors(anchors, newTarget = state.targetValue) }
        val currentTravel by rememberUpdatedState(travel)
        val progress = remember(state, travel) { derivedStateOf { (1f - state.requireOffset() / travel).coerceIn(0f, 1f) } }
        val showMini by remember(progress) { derivedStateOf { progress.value < .4f } }
        val statusCenter = WindowInsets.statusBars.getTop(density) / 2f
        val navigationCenter = heightPx - WindowInsets.navigationBars.getBottom(density) / 2f
        LaunchedEffect(state, origin, heightPx, statusCenter, navigationCenter) {
            snapshotFlow {
                val top = state.requireOffset()
                val bottom = top + (origin?.height ?: heightPx) * (1f - progress.value) + heightPx * progress.value
                (top <= statusCenter && bottom >= statusCenter) to (top <= navigationCenter && bottom >= navigationCenter)
            }.collect { (status, navigation) -> updateBars(status, navigation) }
        }
        val close: () -> Unit = remember(state, scope) {
            {
                scope.launch {
                    state.animateTo(SheetAnchor.Collapsed, AmiMotion.sheet)
                    finishDismiss()
                }
                Unit
            }
        }
        val threshold = remember(density) {
            { distance: Float -> minOf(distance * .22f, with(density) { 96.dp.toPx() }) }
        }
        val defaultFling = AnchoredDraggableDefaults.flingBehavior(
            state, animationSpec = AmiMotion.sheet,
            positionalThreshold = threshold,
        )
        val fling = remember(state, defaultFling) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val remaining = with(defaultFling) { performFling(initialVelocity) }
                    // The modifier updates settledValue after this callback returns.
                    if (state.requireOffset() >= currentTravel - 1f) finishDismiss()
                    return remaining
                }
            }
        }
        val dragHandle = remember(state, fling) {
            Modifier.anchoredDraggable(state, Orientation.Vertical, flingBehavior = fling)
        }
        LaunchedEffect(state) { state.animateTo(SheetAnchor.Expanded, AmiMotion.sheet) }
        PredictiveBackHandler { events ->
            try {
                state.anchoredDrag(dragPriority = MutatePriority.UserInput) {
                    events.collect { dragTo(travel * it.progress.coerceIn(0f, 1f)) }
                }
                state.animateTo(SheetAnchor.Collapsed, AmiMotion.sheet)
                finishDismiss()
            } catch (cancelled: CancellationException) {
                scope.launch { state.animateTo(SheetAnchor.Expanded, AmiMotion.sheet) }
                throw cancelled
            }
        }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value * .24f }
            .background(Color.Black).clickable(onClick = close).clearAndSetSemantics {})
        Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = state.requireOffset()
                clip = true
                shape = PlayerSheetShape(progress.value, origin, heightPx)
            }.testTag("player-sheet")
            .semantics { paneTitle = "正在播放"; dismiss { close(); true } }) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((progress.value - .18f) / .42f).coerceIn(0f, 1f) }) {
                content(close, dragHandle)
            }
            // Retain the exact mini-player face while its surface expands into the full player.
            if (origin != null && showMini) Box(Modifier
                .offset(x = with(density) { origin.left.toDp() })
                .size(with(density) { origin.width.toDp() }, with(density) { origin.height.toDp() })
                .graphicsLayer { alpha = ((.4f - progress.value) / .2f).coerceIn(0f, 1f) }
                .then(dragHandle).clearAndSetSemantics { this[SemanticsProperties.TestTag] = "player-mini-face" }) { collapsedContent() }
        }
    }
}

private class PlayerSheetShape(
    private val progress: Float,
    private val origin: Rect?,
    private val height: Float,
) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val margin = (origin?.left ?: 0f) * (1f - progress)
        val visibleHeight = (origin?.height ?: height) * (1f - progress) + height * progress
        val radius = with(density) { lerp(20.dp, 0.dp, progress).toPx() }
        return Outline.Rounded(RoundRect(margin, 0f, size.width - margin, visibleHeight, CornerRadius(radius)))
    }
}
