package com.telenebula.app.ui.screens.dex

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun DexScreen(viewModel: DexViewModel) {
    Screen(title = "Dex", onBack = viewModel::goBack) {
        Text(
            "Dex is on its way. This is where it will live.",
            style = TnType.body,
            color = TnTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(TnSpace.xl),
        )
    }
}
