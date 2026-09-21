package com.telenebula.app.ui.screens.contact

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Contact as ContactKey
import com.telenebula.app.nav.ContactCalls
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.CallLogPresentation
import com.telenebula.app.platform.CallLogRowData
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.deniedCallPermission
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.calls.CallEngine
import com.telenebula.core.CoreClient
import com.telenebula.core.PresenceStore
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.PeerPresence
import com.telenebula.core.model.PresencePrefs
import com.telenebula.core.model.PeerQueueState
import com.telenebula.core.model.PeerStats
import com.telenebula.vpn.NebulaVpnController
import com.telenebula.vpn.model.HostmapEntry
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PingTint { NONE, ONLINE, REACHABLE, OFFLINE }

data class ContactEdit(val address: String, val nickname: String, val notes: String)

data class ContactUiState(
    val ip: String,
    val name: String = "",
    val nickname: String = "",
    val notes: String = "",
    val presenceText: String = "",
    val addedAtText: String = "",
    val callLogs: List<CallLogRowData> = emptyList(),
    val hasMoreCalls: Boolean = false,
    val isAdvancedOpen: Boolean = false,
    val pingTint: PingTint = PingTint.NONE,
    val latencyText: String = "Tap Ping",
    val isPinging: Boolean = false,
    val isTunnelOn: Boolean = false,
    val connectionStatus: String = "",
    val connectionPath: String = "—",
    val endpoint: String = "—",
    val peerCertName: String = "—",
    val peerCertFingerprint: String = "Available once a tunnel is established",
    val stats: PeerStats = PeerStats(),
    /** actions still waiting to reach this peer */
    val queuedCount: Int = 0,
    val isPeerReachable: Boolean = true,
    val failedCount: Int = 0,
    val firstMessageText: String = "—",
    val lastActivityText: String = "—",
    val clientVersion: String = "—",
    val isBlocked: Boolean = false,
    val edit: ContactEdit? = null,
    val error: String? = null,
) {
    /** What is waiting for this peer; a refusal is counted apart, since retrying cannot fix one. */
    val queueLabel: String
        get() = when {
            queuedCount == 0 && failedCount == 0 -> "Nothing waiting"
            queuedCount == 0 -> "$failedCount refused — tap to try again"
            isPeerReachable -> "$queuedCount waiting · sending now"
            else -> "$queuedCount waiting for them to come back — tap to try now"
        }
}

@Stable
interface ContactActions {
    fun beginEdit()
    fun clearError()
    fun goBack(): Boolean
    fun openAllCalls()
    fun ping()
    fun resetTunnel()
    fun sendQueuedNow()
    fun startAudioCall()
    fun startChat()
    fun startVideoCall()
    fun toggleAdvanced()
}

class ContactViewModel(
    private val ip: String,
    private val core: CoreClient,
    private val peerQueues: PeerQueueStore,
    private val presence: PresenceStore,
    private val prefs: PrefsRepository,
    private val vpn: NebulaVpnController,
    private val runtime: AppRuntime,
    private val callEngine: CallEngine,
    private val gateway: ActivityGateway,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel(), ContactActions {
    private data class Local(
        val isAdvancedOpen: Boolean = false,
        val latency: Long? = null,
        val isPinging: Boolean = false,
        val edit: ContactEdit? = null,
        val error: String? = null,
    )

    private class Details(val logs: List<CallLogRowData>, val hasMore: Boolean, val stats: PeerStats, val host: HostmapEntry?)

    private val local = MutableStateFlow(Local())
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // storeChanges() ticks once at once, then after every store change: this is a tick source, not data
    private val details = combine(merge(core.storeChanges(), refresh), runtime.tunnelRunning, runtime.profile) { _, running, profile ->
        running to (profile?.lighthouses?.map { it.nebulaIp } ?: emptyList())
    }.mapLatest { (running, lighthouses) ->
        val logs = core.callLogs(ip, 11)
        Details(
            logs = logs.take(10).map { CallLogPresentation.toRow(it) },
            hasMore = logs.size > 10,
            stats = core.peerStats(ip),
            host = if (running) vpn.hostInfo(ip, lighthouses) else null,
        )
    }

    val uiState: StateFlow<ContactUiState> =
        combine(core.contactFlow(ip), details, link, peerQueues.queues, local) { contact, d, (running, peerPresence, mine), queues, l ->
            build(contact, d, running, peerPresence, mine, queues[ip], l)
        }.uiState(
            viewModelScope,
            build(core.cachedContact(ip), cachedDetails(), runtime.tunnelRunning.value, presence.of(ip), prefs.prefs.value.core.presence, peerQueues.of(ip), local.value),
        )

    private val link get() = combine(
        runtime.tunnelRunning,
        presence.presence.map { it[ip] }.distinctUntilChanged(),
        prefs.prefs.map { it.core.presence }.distinctUntilChanged(),
    ) { running, p, mine -> Triple(running, p, mine) }

    /** Stats and the host need the tunnel and a query; the recent calls are already in the core's cache. */
    private fun cachedDetails(): Details {
        val logs = core.recentCallLogs.value.orEmpty().filter { it.peerIp == ip }
        return Details(logs.take(10).map { CallLogPresentation.toRow(it) }, hasMore = logs.size > 10, stats = PeerStats(), host = null)
    }

    private fun build(contact: Contact?, d: Details, running: Boolean, peerPresence: PresenceStore.Entry?, mine: PresencePrefs, queue: PeerQueueState?, l: Local): ContactUiState {
        val host = d.host
        val now = System.currentTimeMillis()
        val peerNow = mine.seenAs(PresenceStore.fresh(peerPresence, now), now)
        return ContactUiState(
            ip = ip,
            name = contact?.let(ContactLabels::contactLabel) ?: ip,
            nickname = contact?.nickname.orEmpty(),
            notes = contact?.notes.orEmpty(),
            presenceText = when {
                !running -> "Offline"
                else -> when (peerNow) {
                    PeerPresence.ONLINE -> "Online"
                    PeerPresence.REACHABLE -> "Reachable"
                    PeerPresence.OFFLINE -> "Offline"
                    null -> Format.lastSeen(contact?.lastSeenAt)
                }
            },
            addedAtText = contact?.let { "Added ${Format.listTime(it.addedAt)}" }.orEmpty(),
            callLogs = d.logs,
            hasMoreCalls = d.hasMore,
            isAdvancedOpen = l.isAdvancedOpen,
            pingTint = when {
                l.latency == null -> PingTint.NONE
                l.latency < 0 -> PingTint.OFFLINE
                peerNow == PeerPresence.ONLINE -> PingTint.ONLINE
                else -> PingTint.REACHABLE
            },
            latencyText = when {
                l.isPinging -> "Pinging…"
                l.latency == null -> "Tap Ping"
                l.latency < 0 -> "No answer · offline"
                peerNow == PeerPresence.ONLINE -> "${l.latency} ms · online"
                else -> "${l.latency} ms · reachable"
            },
            isPinging = l.isPinging,
            queuedCount = queue?.queued ?: 0,
            isPeerReachable = queue?.isReachable ?: true,
            failedCount = d.stats.failedActions,
            isTunnelOn = running,
            connectionStatus = when {
                !running -> "Tunnel off"
                d.stats.isConnected -> "Connected (message link open)"
                host != null -> "Tunnel established"
                else -> "Not connected"
            },
            connectionPath = when {
                host == null -> "—"
                host.currentRemote != null -> "Direct"
                host.isRelayed -> "Relayed"
                else -> "Handshaking"
            },
            endpoint = host?.currentRemote ?: host?.remoteAddrs?.firstOrNull() ?: "—",
            peerCertName = host?.certName ?: "—",
            peerCertFingerprint = host?.certFingerprint ?: "Available once a tunnel is established",
            stats = d.stats,
            firstMessageText = d.stats.firstMessageAt?.let(Format::listTime) ?: "—",
            lastActivityText = d.stats.lastActivityAt?.let(Format::listTime) ?: "—",
            clientVersion = contact?.clientVersion ?: "—",
            isBlocked = contact?.isBlocked ?: false,
            edit = l.edit,
            error = l.error,
        )
    }

    /** the same record [uiState] is built from, read without a second subscription */
    private val contact: Contact? get() = core.cachedContact(ip)

    fun onShown() = core.refresh()
    override fun toggleAdvanced() = local.update { it.copy(isAdvancedOpen = !it.isAdvancedOpen) }
    override fun clearError() = local.update { it.copy(error = null) }
    override fun openAllCalls() = navigator.push(ContactCalls(ip))
    override fun startChat() = navigator.push(Chat(ip))
    override fun goBack() = navigator.pop()

    override fun ping() {
        if (local.value.isPinging) return
        local.update { it.copy(isPinging = true) }
        viewModelScope.launch {
            val rtt = core.pingPeer(ip)
            local.update { it.copy(latency = rtt, isPinging = false) }
            refresh.tryEmit(Unit)
        }
    }

    override fun resetTunnel() {
        viewModelScope.launch {
            val ok = vpn.closeTunnel(ip)
            notices.setSuccess(
                if (ok) "Tunnel reset: Nebula will handshake again on the next packet." else "No tunnel to reset: There is no established tunnel to this peer.",
            )
            refresh.tryEmit(Unit)
        }
    }

    /**
     * Probe this peer and move everything it is holding. Nothing is retried per action any more, so
     * the useful thing to ask for is that the peer be tried now rather than at its next probe.
     */
    override fun sendQueuedNow() {
        viewModelScope.launch {
            val queued = uiState.value.queuedCount
            core.retryFailedActions(ip)
            core.drainNow(ip)
            notices.setSuccess(
                if (queued == 0) "Nothing queued: everything for this contact has been delivered."
                else "Sending now: trying ${if (queued == 1) "1 queued action" else "$queued queued actions"}.",
            )
        }
    }

    override fun startAudioCall() = startCall(video = false)
    override fun startVideoCall() = startCall(video = true)

    private fun startCall(video: Boolean) {
        if (contact?.isBlocked == true) {
            notices.addWarning("Contact is blocked: Unblock them to call.")
            return
        }
        viewModelScope.launch {
            gateway.deniedCallPermission(video)?.let { denied ->
                notices.addWarning(denied.needed("start a call"))
                return@launch
            }
            if (callEngine.startCall(ip, video)) {
                local.update { it.copy(error = null) }
                navigator.openCall()
            } else {
                local.update { it.copy(error = "You're already in a call. End it before starting a new one.") }
            }
        }
    }

    fun toggleBlock() {
        if (contact?.isBlocked == true) {
            viewModelScope.launch { core.setContactFlags(ip, ContactFlagsPatch(isBlocked = false)) }
            return
        }
        notices.setPrompt(
            Prompt(
                message = "Block contact\n\nBlock ${contact?.let(ContactLabels::chatLabel) ?: ip}? Their messages and calls will be dropped.",
                rightLabel = "Block",
                isDestructive = true,
                onRight = { viewModelScope.launch { core.setContactFlags(ip, ContactFlagsPatch(isBlocked = true)) } },
            ),
        )
    }

    override fun beginEdit() = local.update { it.copy(edit = ContactEdit(ip, contact?.nickname.orEmpty(), contact?.notes.orEmpty())) }
    fun cancelEdit() = local.update { it.copy(edit = null) }
    fun setEditAddress(v: String) = local.update { it.copy(edit = it.edit?.copy(address = v)) }
    fun setEditNickname(v: String) = local.update { it.copy(edit = it.edit?.copy(nickname = v)) }
    fun setEditNotes(v: String) = local.update { it.copy(edit = it.edit?.copy(notes = v)) }

    fun saveEdit() {
        val edit = local.value.edit ?: return
        val name = contact?.name.orEmpty()
        val nickname = edit.nickname.trim()
        val notes = edit.notes.trim()
        val nextIp = CoreClient.normalizeIp(edit.address)
        if (nextIp == ip) {
            viewModelScope.launch { core.updateContactDetails(ip, name, nickname, notes) }
            local.update { it.copy(edit = null) }
            return
        }
        if (!ContactLabels.isOverlayIp(nextIp)) {
            local.update { it.copy(error = "Enter a valid nebula IPv6 address.") }
            return
        }
        if (nextIp == runtime.profile.value?.overlayIp) {
            local.update { it.copy(error = "That is this device's own address.") }
            return
        }
        viewModelScope.launch {
            val existing = core.contact(nextIp)
            if (existing != null) {
                notices.setPrompt(
                    Prompt(
                        message = "Merge contacts\n\n${ContactLabels.contactLabel(existing)} is already saved at $nextIp. Merge both chats into this contact?",
                        rightLabel = "Merge",
                        isDestructive = true,
                        onRight = { move(nextIp, name, nickname, notes) },
                    ),
                )
            } else {
                move(nextIp, name, nickname, notes)
            }
        }
    }

    /** Moves the chat with the address; a different peer already at that address merges into it. */
    private fun move(nextIp: String, name: String, nickname: String, notes: String) {
        viewModelScope.launch {
            try {
                core.changeContactIp(ip, nextIp)
                core.updateContactDetails(nextIp, name, nickname, notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't move the contact: ${e.userMessage()}")
                return@launch
            }
            local.update { it.copy(edit = null) }
            navigator.dismissToTabRoot()
            navigator.push(ContactKey(nextIp))
        }
    }

    fun confirmDelete() = notices.setPrompt(
        Prompt(
            message = "Delete contact\n\nRemove ${contact?.let(ContactLabels::contactLabel) ?: ip} and the chat history?",
            rightLabel = "Delete",
            isDestructive = true,
            onRight = {
                viewModelScope.launch {
                    core.deleteContact(ip)
                    notices.setSuccess("Contact was deleted.")
                    navigator.pop()
                }
            },
        ),
    )
}
