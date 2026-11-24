package com.longzhousz.ami.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.TextStyle
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
    key(id, artist) {
        ExpandableHeading(
            title,
            "歌名",
            style = LocalTextStyle.current.copy(color = Color.White, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            modifier = modifier.testTag("player-track-heading"),
            titleModifier = Modifier.testTag("player-track-title"),
        ) {
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

@Composable
internal fun ExpandableHeading(
    title: String,
    label: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: @Composable ColumnScope.() -> Unit = {},
) {
    key(title) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var truncated by remember { mutableStateOf(false) }
        val scroll = rememberScrollState()
        val toggle =
            if (enabled && (truncated || expanded)) {
                Modifier
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起$label" else "展开完整$label",
                        onClick = { expanded = !expanded },
                    ).semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            } else {
                Modifier
            }
        Column(modifier.then(toggle)) {
            Text(
                title,
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(dampingRatio = .96f, stiffness = 380f), Alignment.TopStart)
                    .heightIn(max = 160.dp)
                    .verticalScroll(scroll, enabled = expanded)
                    .then(titleModifier),
                style = style,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
            )
            supportingContent()
        }
    }
}
