package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.core.OnlineAlbumOrder

@Composable internal fun LibraryNavigationRefresh(
    request: Int,
    grid: LazyGridState,
    list: LazyListState? = null,
    active: Boolean = true,
    onRefresh: () -> Unit,
) {
    // A detail or another tab can dispose the shelf before the next refresh arrives.
    var appliedRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(request, active) {
        if (active && appliedRequest != request) {
            // Either layout may still be unmeasured, so neither reset can wait for layout.
            grid.requestScrollToItem(0)
            list?.requestScrollToItem(0)
            appliedRequest = request
            onRefresh()
        }
    }
}

@Composable internal fun KeepLibraryAtTop(
    firstKey: String?,
    atTop: () -> Boolean,
    scrollToTop: () -> Unit,
) {
    var previousFirstKey by remember { mutableStateOf(firstKey) }
    if (firstKey != previousFirstKey) {
        SideEffect {
            // While at the start, show the new first card instead of following the previous card's key.
            if (atTop()) scrollToTop()
            previousFirstKey = firstKey
        }
    }
}

@Composable internal fun LibraryShelfGrid(
    state: LazyGridState,
    firstKey: String?,
    contentTop: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    KeepLibraryAtTop(firstKey, { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }) { state.requestScrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(145.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = contentTop, bottom = bottomInset),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        content = content,
    )
}

@Composable internal fun LazyGridItemScope.LibraryShelfCard(
    title: String,
    subtitle: String,
    cover: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
) {
    Column(modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 320f)).clickable(enabled = enabled, onClick = onClick)) {
        Cover(cover, title, Modifier.fillMaxWidth().aspectRatio(1f).then(coverModifier))
        Text(title, Modifier.padding(top = 10.dp, start = 2.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, Modifier.padding(start = 2.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class LibraryCollectionScene<T>(
    val collection: T?,
    val visit: Int,
    val key: String,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun <T> LibraryCollectionTransition(
    collection: T?,
    collectionKey: String?,
    visit: Int,
    content: @Composable SharedTransitionScope.(T?, Int, Boolean, AnimatedVisibilityScope) -> Unit,
) {
    val target = LibraryCollectionScene(collection, visit, collectionKey?.let { "$it:$visit" } ?: "shelf")
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shelfTransition = remember { fadeIn(tween(200, 80)) togetherWith fadeOut(tween(140)) }
        val detailTransition = remember { fadeIn(tween(200, 80)) togetherWith fadeOut(tween(140)) }
        // Independent transforms keep the active scene above the outgoing scene during a reversal.
        SideEffect {
            shelfTransition.targetContentZIndex = if (collection == null) 1f else 0f
            detailTransition.targetContentZIndex = if (collection != null) 1f else 0f
        }
        AnimatedContent(
            target,
            Modifier.fillMaxSize(),
            contentKey = { it.key },
            transitionSpec = { if (targetState.collection == null) shelfTransition else detailTransition },
            label = "library-collection-transition",
        ) { scene ->
            content(scene.collection, scene.visit, scene.key == target.key, this)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.libraryCoverTransition(
    key: String,
    visibility: AnimatedVisibilityScope,
): Modifier =
    Modifier.sharedElement(
        rememberSharedContentState(key),
        animatedVisibilityScope = visibility,
        boundsTransform = { _, _ -> spring(dampingRatio = .9f, stiffness = 320f) },
    )

@Composable internal fun LibrarySourceSwitch(
    local: Boolean,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
    showLocal: Boolean = true,
    albumOrder: OnlineAlbumOrder? = null,
    onAlbumOrder: (OnlineAlbumOrder) -> Unit = {},
) {
    LibrarySegmentRow {
        if (showLocal) LibrarySegment("本地", local, "library-source-local", onLocal)
        Box {
            var menu by remember { mutableStateOf(false) }
            LibrarySegment(
                "网易云",
                !local,
                "library-source-cloud",
                onCloud,
                onLongClick = if (albumOrder == null) null else ({ menu = true }),
            )
            if (albumOrder != null) {
                GlassDropdownMenu(menu, { menu = false }) {
                    OnlineAlbumOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            { Text(order.label) },
                            {
                                menu = false
                                onAlbumOrder(order)
                            },
                            modifier = Modifier.semantics { selected = albumOrder == order },
                            trailingIcon = { if (albumOrder == order) Icon(Icons.Rounded.Check, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable internal fun LibrarySegments(
    labels: List<String>,
    selected: Int,
    tags: List<String>,
    onSelect: (Int) -> Unit,
) {
    LibrarySegmentRow {
        labels.forEachIndexed { index, label ->
            LibrarySegment(label, selected == index, tags[index], { onSelect(index) })
        }
    }
}

@Composable private fun LibrarySegmentRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.liquidGlass(CircleShape, prominent = true).padding(horizontal = 4.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable private fun LibrarySegment(
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val input =
        if (onLongClick == null) {
            Modifier.selectable(selected = active, role = Role.Tab, onClick = onClick)
        } else {
            Modifier
                .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "专辑排列顺序")
                .semantics { selected = active }
        }
    Box(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .then(input)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = .14f) else Color.Transparent)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
