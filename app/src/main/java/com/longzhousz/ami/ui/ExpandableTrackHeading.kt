package com.longzhousz.ami.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Long titles unfold in place; the artwork/lyric stage gives up only the space they need. */
@Composable
internal fun ExpandableTrackHeading(
    id: String,
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
) {
    key(id, title, artist) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var truncated by remember { mutableStateOf(false) }
        val scroll = rememberScrollState()
        val toggle =
            if (truncated || expanded) {
                Modifier
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起歌名" else "展开完整歌名",
                        onClick = { expanded = !expanded },
                    ).semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            } else {
                Modifier
            }
        Column(modifier.then(toggle).testTag("player-track-heading")) {
            Text(
                title,
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(dampingRatio = .96f, stiffness = 380f), Alignment.TopStart)
                    .heightIn(max = 160.dp)
                    .verticalScroll(scroll, enabled = expanded)
                    .testTag("player-track-title"),
                color = Color.White,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
            )
            Text(
                artist,
                Modifier.padding(top = 4.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Color.White.copy(alpha = .56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
