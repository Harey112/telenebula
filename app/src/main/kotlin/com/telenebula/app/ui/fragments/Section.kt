package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import androidx.compose.foundation.shape.RoundedCornerShape

/** Collects the rows of a [Section] so hairlines can be drawn between them. */
class SectionScope internal constructor() {
    internal val rows = ArrayList<@Composable () -> Unit>(8)

    fun row(content: @Composable () -> Unit) {
        rows.add(content)
    }
}

/** A titled group of rows on one flat surface, hairlines between rows. */
@Composable
fun Section(title: String? = null, footnote: String? = null, content: SectionScope.() -> Unit) {
    val colors = TnTheme.colors
    val scope = SectionScope().apply(content)
    Column(modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.xl)) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = TnType.small.copy(letterSpacing = 0.6.sp),
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = TnSpace.sm, start = TnSpace.xs, end = TnSpace.xs),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadius.lg)).background(colors.surface)) {
            scope.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(thickness = Dp.Hairline, color = colors.hairline, modifier = Modifier.padding(start = TnSpace.lg))
                row()
            }
        }
        if (footnote != null) {
            Text(
                text = footnote,
                style = TnType.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(top = TnSpace.sm, start = TnSpace.xs, end = TnSpace.xs),
            )
        }
    }
}
