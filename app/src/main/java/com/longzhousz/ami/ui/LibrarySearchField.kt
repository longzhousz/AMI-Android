package com.longzhousz.ami.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Transition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.R

@Composable
internal fun Transition<Boolean>.LibrarySearchExpansion(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = { it },
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) { content() }
}

@Composable
internal fun LibrarySearchField(
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    OutlinedTextField(
        query,
        onQuery,
        modifier
            .fillMaxWidth()
            .liquidGlass(shape)
            .focusRequester(focus)
            .testTag(tag),
        placeholder = { Text(placeholder) },
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
