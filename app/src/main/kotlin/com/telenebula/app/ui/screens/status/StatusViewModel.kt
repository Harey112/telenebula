package com.telenebula.app.ui.screens.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.model.PresencePrefs
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

data class StatusUiState(
    /** the switch: sharing right now, so a running pause shows it off */
    val isActive: Boolean = true,
    val pauseKey: String = "0",
    val pauseNote: String? = null,
    val seenAs: String = "",
    val openMenuKey: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModel(private val prefs: PrefsRepository, runtime: AppRuntime, private val navigator: Navigator) : ViewModel() {
    private val clock = DateFormat.getTimeInstance(DateFormat.SHORT)

    val pauseOptions: List<SelectOption> = PAUSES.map { (minutes, label) -> SelectOption(minutes.toString(), label) }

    // a pause that runs out re-renders on time, without a store change to prompt it
    private val presence = prefs.prefs.map { it.presence }.distinctUntilChanged().transformLatest { p ->
        emit(p)
        val wait = p.pausedUntil - System.currentTimeMillis()
        if (p.isShared && wait > 0) {
            delay(wait)
            emit(p)
        }
    }

    private val menus = OpenMenu()

    val uiState: StateFlow<StatusUiState> = combine(presence, runtime.tunnelRunning, menus.key, ::build)
        .uiState(viewModelScope, build(prefs.prefs.value.presence, runtime.tunnelRunning.value, null))

    fun openMenu(key: String) = menus.open(key)

    fun closeMenu() = menus.close()

    private fun build(p: PresencePrefs, running: Boolean, openMenuKey: String?): StatusUiState {
        val now = System.currentTimeMillis()
        val isPaused = p.isShared && p.pausedUntil > now
        return StatusUiState(
            isActive = p.isSharingAt(now),
            pauseKey = if (isPaused) p.pauseMinutes.toString() else "0",
            pauseNote = if (isPaused) "Paused until ${clock.format(Date(p.pausedUntil))}; until then contacts see “reachable”." else null,
            seenAs = when {
                !running -> "Offline — the tunnel is off"
                p.isSharingAt(now) -> "Online while you use the app, reachable otherwise"
                else -> "Reachable"
            },
            openMenuKey = openMenuKey,
        )
    }

    /** Off while paused means "end the pause"; otherwise the switch is the sharing flag itself. */
    fun toggleActive() = prefs.update { current ->
        val p = current.presence
        val isPaused = p.isShared && p.pausedUntil > System.currentTimeMillis()
        current.copy(presence = PresencePrefs(isShared = if (isPaused) true else !p.isShared))
    }

    fun setPause(key: String) {
        val minutes = key.toIntOrNull() ?: 0
        val until = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        // a pause implies sharing resumes when it ends, so it re-arms the flag as well
        prefs.update { it.copy(presence = PresencePrefs(isShared = true, pauseMinutes = minutes, pausedUntil = until)) }
    }

    fun goBack() {
        navigator.pop()
    }

    private companion object {
        val PAUSES = listOf(0 to "Off", 30 to "30 minutes", 60 to "1 hour", 480 to "8 hours", 1440 to "24 hours")
    }
}
