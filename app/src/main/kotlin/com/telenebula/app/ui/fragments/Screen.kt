package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme

/**
 * Screen scaffold: themed ground, optional header, and either a scrolling body (default) or a
 * plain body for lists that scroll themselves.
 */
@Composable
fun Screen(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    isScrolling: Boolean = true,
    /** the body rises above the keyboard */
    hasKeyboard: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TnTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .then(if (hasKeyboard) Modifier.imePadding() else Modifier),
    ) {
        if (title != null) ScreenHeader(title = title, onBack = onBack, trailing = trailing)
        if (isScrolling) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = TnSpace.xxl),
                content = content,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
        }
    }
}

/** Fills the remaining space of a non-scrolling screen body. */
@Composable
fun ColumnScope.ScreenBody(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
}
