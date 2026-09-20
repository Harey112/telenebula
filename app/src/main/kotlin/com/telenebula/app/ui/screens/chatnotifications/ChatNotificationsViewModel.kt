package com.telenebula.app.ui.screens.chatnotifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.MUTE_FOREVER
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.core.CoreClient
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ChatNotificationsUiState(
    val title: String = "",
    val isEnabled: Boolean = true,
    val muteStatus: String = "",
    val prefs: ContactNotificationPrefs = ContactNotificationPrefs(),
    val openMenuKey: String? = null,
) {
    val isCustomized: Boolean get() = !prefs.useGlobal
}

class ChatNotificationsViewModel(private val ip: String, private val core: CoreClient, private val navigator: Navigator) : ViewModel() {
    private val menus = OpenMenu()

    val uiState: StateFlow<ChatNotificationsUiState> = combine(core.contactFlow(ip), menus.key, ::build)
        .uiState(viewModelScope, build(core.cachedContact(ip), null))

    fun openMenu(key: String) = menus.open(key)

    fun closeMenu() = menus.close()

    private fun build(contact: Contact?, openMenuKey: String?): ChatNotificationsUiState {
        val muteUntil = contact?.muteUntil ?: 0L
        return ChatNotificationsUiState(
            title = contact?.let(ContactLabels::chatLabel) ?: ip,
            isEnabled = !ContactLabels.isMuted(muteUntil),
            muteStatus = when {
                muteUntil == MUTE_FOREVER -> "Muted"
                ContactLabels.isMuted(muteUntil) -> "Muted until ${Format.listTime(muteUntil)}"
                else -> "Notifications on"
            },
            prefs = contact?.notifications ?: ContactNotificationPrefs(),
            openMenuKey = openMenuKey,
        )
    }

    val muteOptions: List<SelectOption> = MUTE_OPTIONS.map { SelectOption(it.first, it.second) }

    fun toggleEnabled() = flags(ContactFlagsPatch(muteUntil = if (uiState.value.isEnabled) MUTE_FOREVER else 0L))

    fun muteFor(key: String) {
        if (key !in MUTE_MS) return
        // "forever" maps to null on purpose: no duration, the sentinel instead
        val ms = MUTE_MS[key]
        flags(ContactFlagsPatch(muteUntil = if (ms == null) MUTE_FOREVER else System.currentTimeMillis() + ms))
    }

    fun commit(next: ContactNotificationPrefs) = viewModelScope.launch { core.setContactNotifications(ip, next) }
    fun toggleCustomized() = commit(uiState.value.prefs.let { it.copy(useGlobal = !it.useGlobal) })
    fun resetToGlobal() = viewModelScope.launch { core.setContactNotifications(ip, null) }
    fun goBack() = navigator.pop()

    private fun flags(patch: ContactFlagsPatch) = viewModelScope.launch { core.setContactFlags(ip, patch) }

    private companion object {
        val MUTE_OPTIONS = listOf("1h" to "1 hour", "8h" to "8 hours", "1d" to "1 day", "1w" to "1 week", "forever" to "Forever")
        val MUTE_MS: Map<String, Long?> = mapOf("1h" to 3_600_000L, "8h" to 8 * 3_600_000L, "1d" to 24 * 3_600_000L, "1w" to 7 * 24 * 3_600_000L, "forever" to null)
    }
}
