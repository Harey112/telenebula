package com.telenebula.app.ui.screens.about

import androidx.compose.runtime.Composable
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun AboutScreen(viewModel: AboutViewModel) {
    Screen(title = "About", onBack = viewModel::goBack) {
        Section(title = "Version") {
            row { InfoField("TeleNebula", viewModel.appVersion, isMono = true) }
        }
        Section(title = "Help") {
            row { SettingRow(TnIcon.FILE, "Documentation", onClick = viewModel::openDocs) }
            row { SettingRow(TnIcon.RETRY, "Report a problem", help = "Opens the issue tracker on GitHub. Export a diagnostics file from Diagnostics first and attach it to the issue yourself.", onClick = viewModel::openIssues) }
        }
        Section(title = "Open-source licenses") {
            for (item in viewModel.licenses) row { InfoField(item.license, item.name) }
        }
    }
}
