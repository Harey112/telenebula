package com.telenebula.app.ui.screens.dex

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.Format
import com.telenebula.app.runtime.DexController
import com.telenebula.app.runtime.DexStatus
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.model.Prefs
import com.telenebula.dex.Limits
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DexClientRow(val id: String, val label: String, val detail: String)

data class DexUiState(
    val isEnabled: Boolean = false,
    val statusLabel: String = "Off",
    val failure: String? = null,
    /** label → https url, one per way into the phone */
    val urls: List<Pair<String, String>> = emptyList(),
    val fingerprint: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
    val maxClientsKey: String = "2",
    val clients: List<DexClientRow> = emptyList(),
    val openMenuKey: String? = null,
    val editor: DexEditor = DexEditor.None,
    val editorValue: String = "",
) {
    val canEnable: Boolean get() = username.isNotBlank() && hasPassword
}

sealed interface DexEditor {
    data object None : DexEditor
    data object Username : DexEditor
    data object Password : DexEditor
}

@Stable
interface DexActions {
    fun toggleEnabled()
    fun editUsername()
    fun editPassword()
    fun setEditorValue(value: String)
    fun saveEditor()
    fun cancelEditor()
    fun openMenu(key: String)
    fun closeMenu()
    fun setMaxClients(key: String)
    fun copyUrl(url: String)
    fun goBack()
}

class DexViewModel(
    private val dex: DexController,
    prefs: StateFlow<Prefs>,
    private val notices: NoticeCenter,
    private val copyToClipboard: (String) -> Unit,
    private val navigator: Navigator,
) : ViewModel(), DexActions {
    private data class Local(val editor: DexEditor = DexEditor.None, val editorValue: String = "")

    private val local = MutableStateFlow(Local())
    private val menus = OpenMenu()

    val clientOptions: List<SelectOption> = (1..Limits.MAX_CLIENTS).map { SelectOption(it.toString(), if (it == 1) "1 client" else "$it clients") }

    // addresses change with Wi‑Fi and tethering; while the page is open they are re-read on a slow tick
    private val status = dex.status.onStart {
        dex.refreshAddresses()
        viewModelScope.launch {
            while (true) {
                delay(ADDRESS_REFRESH_MS)
                dex.refreshAddresses()
            }
        }
    }

    val uiState: StateFlow<DexUiState> = combine(status, prefs, menus.key, local, ::build)
        .uiState(viewModelScope, build(dex.status.value, prefs.value, null, local.value))

    private fun build(s: DexStatus, p: Prefs, menu: String?, l: Local): DexUiState = DexUiState(
        isEnabled = p.dex.isEnabled,
        statusLabel = when {
            !p.dex.isEnabled -> "Off"
            s.failure != null -> "Not running"
            s.isRunning -> if (s.clients.isEmpty()) "On · no clients" else if (s.clients.size == 1) "On · 1 client" else "On · ${s.clients.size} clients"
            s.isStarting -> "Starting…"
            else -> "Waiting for setup"
        },
        failure = if (p.dex.isEnabled) s.failure else null,
        urls = s.urls,
        fingerprint = s.fingerprint,
        username = p.dex.username,
        hasPassword = p.dex.hasPassword,
        maxClientsKey = p.dex.maxClients.toString(),
        clients = s.clients.map { DexClientRow(it.id, com.telenebula.app.runtime.DexCallBridge.labelOf(it), "since ${Format.clock(it.connectedAt)}") },
        openMenuKey = menu,
        editor = l.editor,
        editorValue = l.editorValue,
    )

    override fun toggleEnabled() {
        val current = uiState.value
        if (!current.isEnabled && !current.canEnable) {
            notices.addWarning("Set a username and a password before turning Dex on.")
            return
        }
        dex.setEnabled(!current.isEnabled)
    }

    override fun editUsername() = local.update { it.copy(editor = DexEditor.Username, editorValue = uiState.value.username) }

    override fun editPassword() = local.update { it.copy(editor = DexEditor.Password, editorValue = "") }

    override fun setEditorValue(value: String) = local.update { it.copy(editorValue = value.take(MAX_FIELD_CHARS)) }

    override fun saveEditor() {
        val l = local.value
        val value = l.editorValue.trim()
        when (l.editor) {
            DexEditor.None -> return
            DexEditor.Username -> {
                if (value.length < MIN_USERNAME_CHARS || value.any { it.isWhitespace() }) {
                    notices.addWarning("Use at least $MIN_USERNAME_CHARS characters and no spaces for the username.")
                    return
                }
                dex.setUsername(value)
            }
            DexEditor.Password -> {
                if (value.length < MIN_PASSWORD_CHARS) {
                    notices.addWarning("Use at least $MIN_PASSWORD_CHARS characters for the password.")
                    return
                }
                viewModelScope.launch { dex.setPassword(value) }
            }
        }
        local.update { it.copy(editor = DexEditor.None, editorValue = "") }
    }

    override fun cancelEditor() = local.update { it.copy(editor = DexEditor.None, editorValue = "") }

    override fun openMenu(key: String) = menus.open(key)

    override fun closeMenu() = menus.close()

    override fun setMaxClients(key: String) {
        key.toIntOrNull()?.let(dex::setMaxClients)
        menus.close()
    }

    override fun copyUrl(url: String) {
        copyToClipboard(url)
        notices.setSuccess("Address copied")
    }

    override fun goBack() {
        navigator.pop()
    }

    companion object {
        const val MENU_CLIENTS = "clients"
        const val MIN_USERNAME_CHARS = 3
        const val MIN_PASSWORD_CHARS = 8
        const val MAX_FIELD_CHARS = 64
        const val ADDRESS_REFRESH_MS = 10_000L
    }
}
