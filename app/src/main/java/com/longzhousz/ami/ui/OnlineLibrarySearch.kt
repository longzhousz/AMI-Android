package com.longzhousz.ami.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.R
import com.longzhousz.ami.core.CloudLibrary
import com.longzhousz.ami.core.CloudLikes
import com.longzhousz.ami.data.CloudLibrarySearchResult
import com.longzhousz.ami.data.CloudMusic
import com.longzhousz.ami.data.searchPersonalLibrary
import kotlinx.coroutines.delay

@Stable
internal class PersonalLibrarySearch {
    var expanded by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var mode by mutableIntStateOf(0)
        private set
    var retry by mutableIntStateOf(0)
        private set
    var requestFocus by mutableStateOf(false)
        private set
    val term get() = query.trim()
    val matching get() = expanded && term.isNotEmpty()

    fun open() {
        expanded = true
        requestFocus = true
    }

    fun close() {
        expanded = false
        requestFocus = false
        mode = 0
        changeQuery("")
    }

    fun changeQuery(value: String) {
        if (term != value.trim()) retry = 0
        query = value
    }

    fun selectMode(value: Int) {
        mode = value
    }

    fun retryFailed() {
        retry++
    }

    fun focusRequested() {
        requestFocus = false
    }
}

@Composable
internal fun rememberPersonalLibraryMatches(
    cloud: CloudMusic,
    library: CloudLibrary,
    favorites: Boolean,
    term: String,
    likes: CloudLikes,
    likesRevision: Long,
    retry: Int,
): CloudLibrarySearchResult {
    val sources = remember(library, favorites) { if (favorites) listOfNotNull(library.liked) else library.collections }
    val session = likes.session
    val favoriteIds = likes.ids.takeIf { favorites && likes.ready }
    val revision = if (favorites) likesRevision else 0L
    var result by remember(cloud, sources, term, session, favoriteIds, revision) { mutableStateOf(CloudLibrarySearchResult()) }
    LaunchedEffect(cloud, sources, term, session, favoriteIds, revision, retry) {
        if (term.isBlank()) return@LaunchedEffect
        val previous = result.takeIf { retry > 0 && it.failedOffsets.isNotEmpty() }
        if (previous == null) delay(220)
        cloud.searchPersonalLibrary(sources, term, session, favorites, previous).collect {
            val currentLikes = cloud.likes.value
            val currentFavorites = !favorites || (cloud.likesRevision.value == revision && currentLikes.ids.takeIf { currentLikes.ready } == favoriteIds)
            if (currentLikes.session == session && currentFavorites) result = it
        }
    }
    return result
}

@Composable
internal fun OnlineLibrarySearchField(
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    placeholder: String,
    tag: String,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    OutlinedTextField(
        query,
        onQuery,
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
            .liquidGlass(shape)
            .focusRequester(focus)
            .testTag(tag),
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, stringResource(R.string.library_search_clear), { onQuery("") })
        },
        singleLine = true,
        shape = shape,
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
internal fun PersonalSearchStatus(
    result: CloudLibrarySearchResult,
    waitingForLibrary: Boolean,
    onRetry: () -> Unit,
) {
    if (result.loading || waitingForLibrary) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("personal-library-search-loading"),
            )
        }
    }
    if (result.failedOffsets.isNotEmpty()) {
        OnlineLibraryNotice(
            stringResource(R.string.online_personal_search_partial),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            TextButton(onRetry, enabled = !result.loading) { Text(stringResource(R.string.online_search_retry)) }
        }
    }
    if (result.tracksLimited) {
        OnlineLibraryNotice(
            stringResource(R.string.online_personal_search_limited),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
internal fun OnlineLibraryNotice(
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}
