package com.telenebula.app.ui.screens.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Blocked
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.core.CoreClient
import com.telenebula.core.model.Contact
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.Profile
import com.telenebula.app.ui.shared.CoverGates
import com.telenebula.app.ui.shared.key
import com.telenebula.app.ui.shared.uiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class PrivacyUiState(
    val isAppLockEnabled: Boolean = false,
    val canUseAppLock: Boolean = false,
    val appLockAfterKey: String = "60",
    val isScreenshotBlocked: Boolean = false,
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    val coverGate: CoverRevealGate = CoverRevealGate.TAP,
    val isCoverGateMenuOpen: Boolean = false,
    val blockedCount: Int = 0,
    val certFingerprint: String = "",
    val certExpiry: String = "",
) {
    val coverGateLabel: String get() = CoverGates.labelOf(coverGate)
}

class PrivacyViewModel(
    private val prefs: PrefsRepository,
    core: CoreClient,
    runtime: AppRuntime,
    appLock: AppLock,
    private val navigator: Navigator,
) : ViewModel() {
    private val canUseAppLock = appLock.canUseDeviceAuth()
    private val blockedCount = core.contacts.map(::blocked)

    private data class Local(val isCoverGateMenuOpen: Boolean = false)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<PrivacyUiState> = combine(prefs.prefs, runtime.profile, blockedCount, local, ::buildState)
        .uiState(viewModelScope, buildState(prefs.prefs.value, runtime.profile.value, blocked(core.contacts.value), local.value))

    private fun blocked(contacts: List<Contact>?): Int = contacts.orEmpty().count { it.isBlocked }

    private fun buildState(p: Prefs, profile: Profile?, blocked: Int, l: Local): PrivacyUiState = PrivacyUiState(
        isAppLockEnabled = p.isAppLockEnabled,
        canUseAppLock = canUseAppLock,
        appLockAfterKey = p.appLockAfterSec.toString(),
        isScreenshotBlocked = p.isScreenshotBlocked,
        sendReadReceipts = p.sendReadReceipts,
        sendTypingIndicators = p.sendTypingIndicators,
        coverGate = p.coverRevealGate,
        blockedCount = blocked,
        isCoverGateMenuOpen = l.isCoverGateMenuOpen,
        certFingerprint = profile?.certFingerprint.orEmpty(),
        certExpiry = profile?.certNotAfter?.let(::formatExpiry).orEmpty(),
    )

    val appLockAfterOptions: List<SelectOption> = listOf(
        SelectOption("0", "Immediately"), SelectOption("60", "1 minute"), SelectOption("300", "5 minutes"), SelectOption("900", "15 minutes"),
    )

    fun openCoverGateMenu() = local.update { it.copy(isCoverGateMenuOpen = true) }

    fun closeCoverGateMenu() = local.update { it.copy(isCoverGateMenuOpen = false) }

    fun coverGateMenu(current: CoverRevealGate): List<MenuOption> = CoverGates.gates(canUseAppLock).map { gate ->
        MenuOption(gate.key, if (gate == current) TnIcon.CHECK else TnIcon.LOCK, CoverGates.labelOf(gate)) {
            local.update { it.copy(isCoverGateMenuOpen = false) }
            prefs.update { it.copy(coverRevealGate = gate) }
        }
    }

    fun toggleAppLock() = prefs.update { it.copy(isAppLockEnabled = !it.isAppLockEnabled) }
    fun setAppLockAfter(key: String) = key.toIntOrNull()?.let { sec -> prefs.update { it.copy(appLockAfterSec = sec) } } ?: Unit
    fun toggleScreenshotBlock() = prefs.update { it.copy(isScreenshotBlocked = !it.isScreenshotBlocked) }
    fun toggleTypingIndicators() = prefs.update { it.copy(sendTypingIndicators = !it.sendTypingIndicators) }
    fun openBlocked() = navigator.push(Blocked)
    fun goBack() = navigator.pop()

    fun toggleReadReceipts() = prefs.update { it.copy(sendReadReceipts = !it.sendReadReceipts) }

    private fun formatExpiry(notAfter: String): String = runCatching {
        Instant.parse(notAfter).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault(notAfter)
}
