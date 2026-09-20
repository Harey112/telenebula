package com.telenebula.app.ui.screens.calls

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Contact
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.CallLogPresentation
import com.telenebula.app.platform.CallLogRowData
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.deniedCallPermission
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.shared.uiState
import com.telenebula.calls.CallEngine
import com.telenebula.core.CoreClient
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.CallOutcome
import com.telenebula.core.model.Contact as PeerContact
import com.telenebula.core.model.MessageDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CallFilter(val key: String, val label: String) {
    ALL("all", "All"),
    MISSED("missed", "Missed"),
    INCOMING("in", "Incoming"),
    OUTGOING("out", "Outgoing"),
    VIDEO("video", "Video"),
    ;

    companion object {
        fun fromKey(key: String): CallFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

enum class CallSort(val key: String, val label: String) {
    NEWEST("newest", "Newest first"),
    OLDEST("oldest", "Oldest first"),
    NAME("name", "Name"),
    LONGEST("longest", "Longest"),
    ;

    companion object {
        fun fromKey(key: String): CallSort = entries.firstOrNull { it.key == key } ?: NEWEST
    }
}

data class CallsUiState(
    /** false only before the very first read of the store: no "no calls yet" hint yet */
    val isLoaded: Boolean = false,
    val rows: List<CallLogRowData> = emptyList(),
    /** the store holds calls even if none survive the filter and search */
    val hasCalls: Boolean = false,
    val isTunnelOn: Boolean = false,
    val error: String? = null,
    val isSearching: Boolean = false,
    val query: String = "",
    val filter: CallFilter = CallFilter.ALL,
    val sort: CallSort = CallSort.NEWEST,
    val isFilterPanelOpen: Boolean = false,
    val openMenuKey: String? = null,
    /** long-pressed rows; non-empty means the list is in selection mode */
    val selectedIds: Set<String> = emptySet(),
) {
    val isFiltered: Boolean get() = filter != CallFilter.ALL || sort != CallSort.NEWEST
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()
}

@Stable
interface CallsActions {
    fun openPeer(ip: String)
    fun callBack(ip: String, isVideo: Boolean)
    fun clearError()
    fun beginSearch()
    fun endSearch()
    fun setQuery(value: String)
    fun toggleFilterPanel()
    fun setFilter(key: String)
    fun setSort(key: String)
    fun openMenu(key: String)
    fun closeMenu()
    fun tapRow(id: String, peerIp: String)
    fun longPressRow(id: String)
    fun clearSelection()
    fun deleteSelected()
}

/** Global call history across every contact (the Calls tab). */
class CallsViewModel(
    private val core: CoreClient,
    runtime: AppRuntime,
    private val callEngine: CallEngine,
    private val gateway: ActivityGateway,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel(), CallsActions {
    private data class Local(
        val error: String? = null,
        val isSearching: Boolean = false,
        val query: String = "",
        val filter: CallFilter = CallFilter.ALL,
        val sort: CallSort = CallSort.NEWEST,
        val isFilterPanelOpen: Boolean = false,
        val openMenu: String? = null,
        val selected: Set<String> = emptySet(),
    )

    private val local = MutableStateFlow(Local())

    val filterOptions: List<SelectOption> = CallFilter.entries.map { SelectOption(it.key, it.label) }
    val sortOptions: List<SelectOption> = CallSort.entries.map { SelectOption(it.key, it.label) }

    // seeded from the core's last read, so a tab switch or a relaunch paints the list on its first frame
    val uiState: StateFlow<CallsUiState> = combine(core.recentCallLogs, core.contacts, runtime.tunnelRunning, local, ::build)
        .uiState(viewModelScope, build(core.recentCallLogs.value, core.contacts.value, runtime.tunnelRunning.value, local.value))

    private fun build(logs: List<CallLog>?, contacts: List<PeerContact>?, running: Boolean, l: Local): CallsUiState {
        val labels = HashMap<String, String>()
        for (c in contacts.orEmpty()) labels[c.ip] = ContactLabels.chatLabel(c)
        fun label(log: CallLog) = labels[log.peerIp] ?: log.peerIp

        val needle = if (l.isSearching) l.query.trim().lowercase() else ""
        val matching = logs.orEmpty().filter { log ->
            matches(log, l.filter) && (needle.isEmpty() || label(log).lowercase().contains(needle) || log.peerIp.contains(needle))
        }
        val sorted = when (l.sort) {
            CallSort.NEWEST -> matching.sortedByDescending { it.startedAt }
            CallSort.OLDEST -> matching.sortedBy { it.startedAt }
            CallSort.NAME -> matching.sortedWith(compareBy<CallLog, String>(String.CASE_INSENSITIVE_ORDER, ::label).thenByDescending { it.startedAt })
            CallSort.LONGEST -> matching.sortedWith(compareByDescending<CallLog> { talkTime(it) }.thenByDescending { it.startedAt })
        }
        return CallsUiState(
            isLoaded = logs != null,
            rows = sorted.map { CallLogPresentation.toRow(it, label(it)) },
            hasCalls = !logs.isNullOrEmpty(),
            isTunnelOn = running,
            error = l.error,
            isSearching = l.isSearching,
            query = l.query,
            filter = l.filter,
            sort = l.sort,
            isFilterPanelOpen = l.isFilterPanelOpen,
            openMenuKey = l.openMenu,
            // a row deleted or filtered out of view cannot stay selected
            selectedIds = if (l.selected.isEmpty()) l.selected else sorted.mapTo(HashSet()) { it.id }.apply { retainAll(l.selected) },
        )
    }

    private fun matches(log: CallLog, filter: CallFilter): Boolean = when (filter) {
        CallFilter.ALL -> true
        CallFilter.MISSED -> log.direction == MessageDirection.IN && (log.outcome == CallOutcome.MISSED || log.outcome == CallOutcome.DECLINED)
        CallFilter.INCOMING -> log.direction == MessageDirection.IN
        CallFilter.OUTGOING -> log.direction == MessageDirection.OUT
        CallFilter.VIDEO -> log.isVideo
    }

    private fun talkTime(log: CallLog): Long {
        val connectedAt = log.connectedAt ?: return 0L
        return if (log.outcome == CallOutcome.ANSWERED) (log.endedAt - connectedAt).coerceAtLeast(0L) else 0L
    }

    fun onShown() = core.refresh()

    override fun openPeer(ip: String) = navigator.push(Contact(ip))

    override fun clearError() = local.update { it.copy(error = null) }

    override fun beginSearch() = local.update { it.copy(isSearching = true) }

    override fun endSearch() = local.update { it.copy(isSearching = false, query = "") }

    override fun setQuery(value: String) = local.update { it.copy(query = value) }

    override fun toggleFilterPanel() = local.update { it.copy(isFilterPanelOpen = !it.isFilterPanelOpen) }

    override fun setFilter(key: String) = local.update { it.copy(filter = CallFilter.fromKey(key)) }

    override fun setSort(key: String) = local.update { it.copy(sort = CallSort.fromKey(key)) }

    override fun openMenu(key: String) = local.update { it.copy(openMenu = key) }

    override fun closeMenu() = local.update { it.copy(openMenu = null) }

    override fun tapRow(id: String, peerIp: String) {
        if (uiState.value.isSelecting) toggleSelected(id) else navigator.push(Contact(peerIp))
    }

    override fun longPressRow(id: String) = toggleSelected(id)

    override fun clearSelection() = local.update { it.copy(selected = emptySet()) }

    override fun deleteSelected() {
        val ids = uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        notices.setPrompt(
            Prompt(
                message = "Delete ${ids.size} call${if (ids.size == 1) "" else "s"} from the history on this device?",
                rightLabel = "Delete",
                isDestructive = true,
                onRight = {
                    viewModelScope.launch {
                        core.deleteCallLogs(ids)
                        clearSelection()
                    }
                },
            ),
        )
    }

    private fun toggleSelected(id: String) = local.update { l ->
        l.copy(selected = if (id in l.selected) l.selected - id else l.selected + id)
    }

    override fun callBack(ip: String, isVideo: Boolean) {
        viewModelScope.launch {
            gateway.deniedCallPermission(isVideo)?.let { denied ->
                local.update { it.copy(error = denied.needed("start a call")) }
                return@launch
            }
            if (callEngine.startCall(ip, isVideo)) {
                local.update { it.copy(error = null) }
                navigator.openCall()
            } else {
                local.update { it.copy(error = "You're already in a call. End it before starting a new one.") }
            }
        }
    }
}
