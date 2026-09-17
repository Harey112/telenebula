package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** A tab's title row that turns into an inline search field; [trailing] sits before the search icon. */
@Composable
fun SearchHeader(
    title: String,
    isSearching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onBeginSearch: () -> Unit,
    onEndSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    searchLabel: String = "Search",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = TnTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(TnRow.height).padding(horizontal = TnSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        if (isSearching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TnType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(TnRadius.md))
                    .background(colors.surfaceRaised)
                    .focusRequester(focus)
                    .semantics { contentDescription = searchLabel },
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.md), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(placeholder, style = TnType.body, color = colors.textMuted)
                        inner()
                    }
                },
            )
            Icon(TnIcon.CLOSE, tint = colors.textMuted, contentDescription = "Close search", modifier = Modifier.clickable(role = Role.Button, onClick = onEndSearch))
        } else {
            Text(title, style = TnType.heading, color = colors.text)
            Spacer(modifier = Modifier.weight(1f))
            trailing()
            Icon(TnIcon.SEARCH, tint = colors.text, size = 23.dp, contentDescription = searchLabel, modifier = Modifier.clickable(role = Role.Button, onClick = onBeginSearch))
        }
    }
}
