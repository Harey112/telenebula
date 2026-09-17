package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

enum class ButtonVariant { PRIMARY, SECONDARY }

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    isBusy: Boolean = false,
    /** secondary: soft accent surface with accent text */
    variant: ButtonVariant = ButtonVariant.PRIMARY,
) {
    val colors = TnTheme.colors
    val isSecondary = variant == ButtonVariant.SECONDARY
    val isActive = isEnabled && !isBusy
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (isActive) 1f else 0.45f)
            .clip(RoundedCornerShape(TnRadius.md))
            .background(if (isSecondary) colors.accentSoft else colors.accent)
            .clickable(enabled = isActive, role = Role.Button, onClick = onClick)
            .padding(horizontal = TnSpace.lg),
        contentAlignment = Alignment.Center,
    ) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = if (isSecondary) colors.accent else colors.onAccent)
        } else {
            Text(label, style = TnType.title, color = if (isSecondary) colors.accent else colors.onAccent)
        }
    }
}
