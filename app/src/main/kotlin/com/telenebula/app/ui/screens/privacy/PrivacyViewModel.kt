package com.telenebula.app.ui.screens.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Blocked
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.core.CoreClient
import com.telenebula.core.model.Contact
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.Profile
import com.telenebula.app.ui.shared.CoverGates
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.key
import com.telenebula.app.ui.shared.uiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class PrivacyUiState(
    val isAppLockEnabled: Boolean = false,
    val canUseAppLock: Boolean = false,
    val appLockAfterKey: String = "60",
    val isScreenshotBlocked: Boolean = false,
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    val coverGateKey: String = CoverRevealGate.TAP.key,
    val blockedCount: Int = 0,
    val certFingerprint: String = "",
    val certExpiry: String = "",
    val openMenuKey: String? = null,
)

class PrivacyViewModel(
    private val prefs: PrefsRepository,
    core: CoreClient,
    runtime: AppRuntime,
    appLock: AppLock,
    private val navigator: Navigator,
) : ViewModel() {
    private val canUseAppLock = appLock.canUseDeviceAuth()
    private val blockedCount = core.contacts.map(::blocked)

    private val menus = OpenMenu()

    val uiState: StateFlow<PrivacyUiState> = combine(prefs.prefs, runtime.profile, blockedCount, menus.key, ::buildState)
        .uiState(viewModelScope, buildState(prefs.prefs.value, runtime.profile.value, blocked(core.contacts.value), null))

    fun openMenu(key: String) = menus.open(key)

    fun closeMenu() = menus.close()

    private fun blocked(contacts: List<Contact>?): Int = contacts.orEmpty().count { it.isBlocked }

    private fun buildState(p: Prefs, profile: Profile?, blocked: Int, openMenuKey: String?): PrivacyUiState = PrivacyUiState(
        isAppLockEnabled = p.isAppLockEnabled,
        canUseAppLock = canUseAppLock,
        appLockAfterKey = p.appLockAfterSec.toString(),
        isScreenshotBlocked = p.isScreenshotBlocked,
        sendReadReceipts = p.sendReadReceipts,
        sendTypingIndicators = p.sendTypingIndicators,
        coverGateKey = p.coverRevealGate.key,
        blockedCount = blocked,
        certFingerprint = profile?.certFingerprint.orEmpty(),
        certExpiry = profile?.certNotAfter?.let(::formatExpiry).orEmpty(),
        openMenuKey = openMenuKey,
    )

    val appLockAfterOptions: List<SelectOption> = listOf(
        SelectOption("0", "Immediately"), SelectOption("60", "1 minute"), SelectOption("300", "5 minutes"), SelectOption("900", "15 minutes"),
    )

    val coverGateOptions: List<SelectOption> = CoverGates.options(canUseAppLock, withDefault = false)

    fun setCoverGate(key: String) {
        val gate = CoverGates.of(key) ?: return
        prefs.update { it.copy(coverRevealGate = gate) }
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
