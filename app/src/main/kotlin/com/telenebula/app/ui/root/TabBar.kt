package com.telenebula.app.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.telenebula.app.nav.Tab
import com.telenebula.app.ui.fragments.TabButton
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme

/** The pill bar: Chats, Contacts, Calls, Me. */
@Composable
fun TabBar(current: Tab?, onSelect: (Tab) -> Unit) {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding()) {
        HorizontalDivider(thickness = Dp.Hairline, color = colors.hairline)
        Row(modifier = Modifier.fillMaxWidth().padding(top = TnSpace.xs, bottom = TnSpace.xs)) {
            TabButton(TnIcon.CHATS, "Chats", current == Tab.CHATS, { onSelect(Tab.CHATS) }, Modifier.weight(1f))
            TabButton(TnIcon.CONTACTS, "Contacts", current == Tab.CONTACTS, { onSelect(Tab.CONTACTS) }, Modifier.weight(1f))
            TabButton(TnIcon.CALL, "Calls", current == Tab.CALLS, { onSelect(Tab.CALLS) }, Modifier.weight(1f))
            TabButton(TnIcon.ACCOUNT, "Me", current == Tab.ME, { onSelect(Tab.ME) }, Modifier.weight(1f))
        }
    }
}
