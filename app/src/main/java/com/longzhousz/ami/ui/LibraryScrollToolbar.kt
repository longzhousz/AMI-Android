package com.longzhousz.ami.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Stable
internal class LibraryScrollToolbarState(
    private val scroll: ScrollableState?,
    density: Density,
    private val interactionPinned: () -> Boolean,
) : NestedScrollConnection {
    private val hideDistance = with(density) { 40.dp.toPx() }
    private val showDistance = with(density) { 16.dp.toPx() }
    private val flingDistance = with(density) { 8.dp.toPx() }
    private val flingSpeed = with(density) { 1000.dp.toPx() }
    private var directionDistance = 0f
    private var dragForwardDistance = 0f
    private var hidden by mutableStateOf(false)

    val observesScroll: Boolean get() = scroll != null
    val pinned: Boolean get() = scroll?.canScrollBackward != true || interactionPinned()
    val visible: Boolean get() = !hidden || pinned

    fun reset() {
        hidden = false
        directionDistance = 0f
        beginDrag()
    }

    fun beginDrag() {
        dragForwardDistance = 0f
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val dy = consumed.y
        if (source != NestedScrollSource.UserInput || dy == 0f) return Offset.Zero
        if (pinned) {
            reset()
            return Offset.Zero
        }
        // The lazy layout has already consumed this delta. Neither overscroll nor the fling adds to it.
        dragForwardDistance = if (dy < 0f) dragForwardDistance - dy else 0f
        directionDistance = if ((dy > 0f) == hidden) directionDistance + abs(dy) else 0f
        val threshold = if (hidden) showDistance else hideDistance
        if (directionDistance >= threshold) {
            hidden = !hidden
            directionDistance = 0f
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (pinned) {
            reset()
        } else if (!hidden && available.y <= -flingSpeed && dragForwardDistance >= flingDistance) {
            hidden = true
            directionDistance = 0f
        }
        beginDrag()
        return Velocity.Zero
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberLibraryScrollToolbar(
    scroll: ScrollableState?,
    refreshRequest: Int,
    pullState: PullToRefreshState,
    pinned: () -> Boolean,
): LibraryScrollToolbarState {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val touchExploration = rememberTouchExplorationEnabled()
    val currentPinned by rememberUpdatedState(pinned)
    val currentPullState by rememberUpdatedState(pullState)
    val toolbar =
        remember(scroll, density, configuration.screenWidthDp, configuration.screenHeightDp, statusTop, refreshRequest) {
            LibraryScrollToolbarState(scroll, density) {
                touchExploration.value || currentPullState.distanceFraction > 0f || currentPinned()
            }
        }
    if (toolbar.observesScroll) {
        LaunchedEffect(toolbar) {
            snapshotFlow { toolbar.pinned }.collect { if (it && toolbar.pinned) toolbar.reset() }
        }
    }
    return toolbar
}

internal fun Modifier.observeLibraryScroll(toolbar: LibraryScrollToolbarState): Modifier =
    if (toolbar.observesScroll) {
        nestedScroll(toolbar).pointerInput(toolbar) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                toolbar.beginDrag()
            }
        }
    } else {
        this
    }

@Composable
private fun rememberTouchExplorationEnabled(): State<Boolean> {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val enabled = remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled.value = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        enabled.value = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

internal val Transition<Boolean>.hasVisibleContent: Boolean get() = currentState || targetState || isRunning

@Composable
internal fun LibraryFloatingToolbar(
    visibility: Transition<Boolean>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val travel = with(LocalDensity.current) { 8.dp.roundToPx() }
    visibility.AnimatedVisibility(
        visible = { it },
        modifier = modifier,
        enter = fadeIn(tween(170)) + slideInVertically(tween(170)) { -travel },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -travel },
    ) { content() }
}
