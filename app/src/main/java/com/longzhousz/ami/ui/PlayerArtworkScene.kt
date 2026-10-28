package com.longzhousz.ami.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.launch

private enum class ArtworkPage { Artwork, Lyrics }
private val ArtworkSpring = spring<Float>(dampingRatio = .88f, stiffness = 230f)

/** One image moves between two measured anchors. Frame updates only move/draw layers. */
@Composable internal fun PlayerArtworkScene(
    song: Song,
    savedLyrics: MutableState<Boolean>,
    header: @Composable () -> Unit,
    lyrics: @Composable () -> Unit,
    controls: @Composable (lyricsMode: Boolean, progress: State<Float>, thumbnail: Modifier, toggle: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestSavedLyrics by rememberUpdatedState(savedLyrics)
    val lyricState = key(song.id) { rememberSaveableStateHolder() }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    var thumbnailBounds by remember { mutableStateOf(Rect.Zero) }
    var artworkBounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().onGloballyPositioned { rootBounds = it.boundsInRoot() }) {
        val landscape = maxWidth > 600.dp && maxWidth > maxHeight
        val distance = with(density) { minOf(maxHeight * .45f, 280.dp).toPx().coerceAtLeast(1f) }
        val anchors = remember(distance) { DraggableAnchors { ArtworkPage.Artwork at 0f; ArtworkPage.Lyrics at distance } }
        val state = remember { AnchoredDraggableState(if (savedLyrics.value) ArtworkPage.Lyrics else ArtworkPage.Artwork).apply { updateAnchors(anchors) } }
        SideEffect { state.updateAnchors(anchors, newTarget = state.targetValue) }
        val progress = remember(state, distance) { derivedStateOf { (state.requireOffset() / distance).coerceIn(0f, 1f) } }
        val lyricsMode by remember(state) { derivedStateOf { state.targetValue == ArtworkPage.Lyrics } }
        val renderLyrics by remember(progress) { derivedStateOf { progress.value > .005f } }
        val fling = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = ArtworkSpring, positionalThreshold = { it * .28f })
        val gesture = Modifier.anchoredDraggable(state, Orientation.Vertical, flingBehavior = fling,
            enabled = state.settledValue==ArtworkPage.Artwork || state.isAnimationRunning, startDragImmediately = false)
        val toggle: () -> Unit = {
            val next = if (state.targetValue == ArtworkPage.Lyrics) ArtworkPage.Artwork else ArtworkPage.Lyrics
            savedLyrics.value = next == ArtworkPage.Lyrics
            scope.launch { state.animateTo(next, ArtworkSpring) }
        }
        LaunchedEffect(song.id, savedLyrics) {
            state.animateTo(if (savedLyrics.value) ArtworkPage.Lyrics else ArtworkPage.Artwork, ArtworkSpring)
        }
        LaunchedEffect(state) { snapshotFlow { state.targetValue }.collect { latestSavedLyrics.value = it == ArtworkPage.Lyrics } }
        val thumbnail = Modifier.size(48.dp).testTag("artwork-thumbnail-anchor")
            .onGloballyPositioned { thumbnailBounds = it.boundsInRoot() }
        val stage: @Composable (Modifier) -> Unit = { modifier ->
            BoxWithConstraints(modifier) {
                val side = minOf(maxWidth - 48.dp, maxHeight - 24.dp).coerceAtLeast(48.dp)
                Box(Modifier.size(side).align(Alignment.Center).testTag("artwork-expanded-anchor")
                    .onGloballyPositioned { artworkBounds = it.boundsInRoot() })
                if (renderLyrics) Box(Modifier.fillMaxSize().graphicsLayer {
                    val fraction = progress.value
                    alpha = fraction * fraction
                    translationY = (1f - fraction) * -20.dp.toPx()
                }) { lyricState.SaveableStateProvider(song.id) { lyrics() } }
            }
        }
        // The gesture owner stays still while its image moves/scales under the finger.
        Box(Modifier.fillMaxSize().then(gesture)) {
        Column(Modifier.fillMaxSize()) {
            header()
            if (landscape) Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                stage(Modifier.weight(.55f).fillMaxHeight())
                Column(Modifier.weight(.45f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    controls(lyricsMode, progress, thumbnail, toggle)
                }
            } else {
                stage(Modifier.weight(1f).fillMaxWidth())
                controls(lyricsMode, progress, thumbnail, toggle)
            }
        }
        if (artworkBounds.width > 0f && thumbnailBounds.width > 0f) {
            val side = artworkBounds.width
            // Root-relative anchors keep system bars, font scaling and window resizing honest.
            Box(Modifier.size(with(density) { side.toDp() }).graphicsLayer {
                val p = progress.value
                // Lift clear of the title row before growing; reverse shrinks before landing.
                val smallScale = thumbnailBounds.width / side
                val growth = (1f-p)*(1f-p)
                val scale = smallScale + (1f-smallScale)*growth
                translationX = artworkBounds.left + (thumbnailBounds.left - artworkBounds.left) * p - rootBounds.left
                translationY = artworkBounds.top + (thumbnailBounds.top - artworkBounds.top) * p - rootBounds.top
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
                shape = androidx.compose.ui.graphics.RectangleShape
                clip = true
                shadowElevation = 0f
            }.clickable(onClickLabel = if (lyricsMode) "展开专辑封面" else "收起封面，查看歌词", onClick = toggle)
                .semantics { contentDescription = if (lyricsMode) "展开专辑封面" else "收起封面，查看歌词" }
                .testTag("player-cover")) {
                Cover(song.cover, song.album, Modifier.fillMaxSize())
            }
        }
        }
    }
}
