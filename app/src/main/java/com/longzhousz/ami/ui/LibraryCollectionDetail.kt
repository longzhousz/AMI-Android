package com.longzhousz.ami.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.core.durationLabel

@Composable
internal fun LibraryCollectionDetail(
    title: String,
    cover: String?,
    artist: String,
    trackCount: Int?,
    duration: Long?,
    listState: LazyListState,
    bottomInset: Dp,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    trackListModifier: Modifier = Modifier,
    active: Boolean = true,
    padStatusBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    tracks: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        // Keep the toolbar inside the detail scene so it cannot move the shared transition's origin.
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (padStatusBar) Modifier.statusBarsPadding().padding(top = 12.dp) else Modifier)
                .padding(start = 8.dp, end = 24.dp, bottom = 12.dp)
                .testTag("collection-toolbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回曲库", onBack, enabled = active)
            Spacer(Modifier.width(8.dp))
            ExpandableHeading(
                title,
                "标题",
                MaterialTheme.typography.titleLarge,
                Modifier.weight(1f).testTag("collection-heading"),
                titleModifier = Modifier.testTag("collection-title"),
                enabled = active,
            )
            actions()
        }
        LazyColumn(
            state = listState,
            modifier = trackListModifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottomInset),
        ) {
            item(key = "collection-header") {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(
                        cover,
                        title,
                        Modifier
                            .fillMaxWidth(.68f)
                            .widthIn(max = 280.dp)
                            .aspectRatio(1f)
                            .then(coverModifier)
                            .testTag("collection-cover"),
                    )
                    val style = MaterialTheme.typography.bodySmall
                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                    if (artist.isNotBlank()) {
                        Text(artist, Modifier.padding(top = 12.dp), style = style, color = color)
                    }
                    if (trackCount != null) {
                        val summary = if (duration != null) "$trackCount 首歌曲 · ${durationLabel(duration)}" else "$trackCount 首歌曲"
                        Text(summary, Modifier.padding(top = if (artist.isBlank()) 12.dp else 4.dp), style = style, color = color)
                    }
                }
            }
            tracks()
        }
    }
}
