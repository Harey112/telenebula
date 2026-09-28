package com.telenebula.app.ui.screens.network

import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.FirewallProto
import com.telenebula.core.model.NebulaFirewall
import com.telenebula.core.model.RequiredRules
import com.telenebula.core.nebula.NebulaConfigRepository
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Contact
import com.telenebula.app.nav.Diagnostics
import com.telenebula.app.nav.Lighthouse
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.reporting
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.fragments.PeerPath
import com.telenebula.app.ui.fragments.PeerRow
import com.telenebula.core.CoreClient
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.NetworkStats
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.Profile
import com.telenebula.vpn.NebulaVpnController
import com.telenebula.vpn.model.HostmapEntry
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

data class NetworkUiState(
    val isVpnRunning: Boolean = false,
    val tunnelUptime: String = "—",
    val engineUptime: String = "—",
    val connectedCount: Int = 0,
    val username: String = "",
    val overlayIp: String = "",
    val networks: String = "",
    val ports: String = "",
    val lighthouseIp: String = "",
    val lighthouseUnderlay: String = "",
    val lighthouseStatus: String = "",
    val relayStatus: String = "",
    val peers: List<PeerRow> = emptyList(),
    val pendingHandshakes: Int = 0,
    val bytesSent: String = "",
    val bytesReceived: String = "",
    val pendingActions: Int = 0,
    val failedActions: Int = 0,
    val isDeveloperMode: Boolean = false,
    val isVerboseLogging: Boolean = false,
    val isStartOnBoot: Boolean = true,
    val firewallRules: List<String> = emptyList(),
    val lighthouseDetail: String = "",
)

/** hostmap + core stats are snapshots: taken on open, on Refresh, on return to the foreground and on every db change */
private class Snapshot(
    val stats: NetworkStats,
    val hostmap: List<HostmapEntry>,
    val pending: Int,
    val tunnelUptimeMs: Long,
    val contactsByIp: Map<String, String>,
)

class NetworkViewModel(
    private val core: CoreClient,
    private val vpn: NebulaVpnController,
    private val runtime: AppRuntime,
    private val prefs: PrefsRepository,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
    private val nebulaConfig: NebulaConfigRepository,
) : ViewModel() {
    private val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.RESUMED) }
        .distinctUntilChanged()
        .filter { it }

    private val snapshots = merge(manual, foreground, runtime.tunnelRunning, core.storeChanges())
        .mapLatest {
            val profile = runtime.profile.value
            val lighthouseIps = profile?.lighthouses?.map { it.nebulaIp } ?: emptyList()
            Snapshot(
                stats = core.networkStats(),
                hostmap = vpn.listHostmap(lighthouseIps),
                pending = vpn.listHostmap(lighthouseIps, pending = true).size,
                tunnelUptimeMs = vpn.uptimeMs(),
                contactsByIp = core.readContacts().associate { it.ip to ContactLabels.chatLabel(it) },
            )
        }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<NetworkUiState> = combine(snapshots, runtime.profile, runtime.tunnelRunning, prefs.prefs, ::build)
        .uiState(viewModelScope, NetworkUiState())

    private fun build(snap: Snapshot, profile: Profile?, isRunning: Boolean, p: Prefs): NetworkUiState {
        val peers = snap.hostmap.asSequence()
            .filter { it.vpnIp != profile?.overlayIp }
            .map { h ->
                val contactLabel = snap.contactsByIp[h.vpnIp]
                PeerRow(
                    ip = h.vpnIp,
                    label = contactLabel ?: h.certName ?: h.vpnIp,
                    endpoint = h.currentRemote ?: h.remoteAddrs.firstOrNull() ?: "no endpoint yet",
                    path = when {
                        h.currentRemote != null -> PeerPath.DIRECT
                        h.isRelayed -> PeerPath.RELAYED
                        else -> PeerPath.HANDSHAKING
                    },
                    isLighthouse = h.isLighthouse,
                    isContact = contactLabel != null,
                )
            }
            .toList()
        val lighthouse = profile?.lighthouses?.firstOrNull()
        // the status is read from the same lighthouse the row names, not from whichever one answered first
        val lighthouseStatus = when {
            !isRunning -> "Tunnel off"
            else -> snap.hostmap.firstOrNull { it.isLighthouse && it.vpnIp == lighthouse?.nebulaIp }
                ?.let { "Connected via ${it.currentRemote ?: it.remoteAddrs.firstOrNull() ?: "unknown endpoint"}" }
                ?: "Not reached yet"
        }
        val msgPort = profile?.msgPort ?: 4433
        val nebula = profile?.nebula ?: NebulaAdvancedConfig()
        val mtu = nebula.tun.mtu
        // mirrors the renderer's rule: no list + useRelays → the lighthouse relays
        val relayStatus = when {
            !nebula.relay.useRelays -> "Relays off"
            nebula.relay.relays.isNotEmpty() -> "Custom relays: ${nebula.relay.relays.size}"
            else -> "Via lighthouse relay"
        }
        return NetworkUiState(
            isVpnRunning = isRunning,
            tunnelUptime = if (isRunning && snap.tunnelUptimeMs > 0) Format.uptime(snap.tunnelUptimeMs) else "—",
            engineUptime = if (snap.stats.uptimeMs > 0) Format.uptime(snap.stats.uptimeMs) else "—",
            connectedCount = snap.stats.connectedPeers.size,
            username = profile?.certName ?: "",
            overlayIp = profile?.overlayIp ?: "",
            networks = profile?.networks?.joinToString(", ") ?: "",
            ports = "UDP ${profile?.listenPort ?: ""} · TCP $msgPort · MTU $mtu",
            lighthouseIp = lighthouse?.nebulaIp ?: "",
            lighthouseUnderlay = lighthouse?.underlay ?: "",
            lighthouseStatus = lighthouseStatus,
            lighthouseDetail = listOfNotNull(lighthouse?.underlay?.takeIf { it.isNotBlank() }, lighthouseStatus).joinToString(" · "),
            relayStatus = relayStatus,
            peers = peers,
            pendingHandshakes = snap.pending,
            bytesSent = Format.bytes(snap.stats.bytesSent),
            bytesReceived = Format.bytes(snap.stats.bytesReceived),
            pendingActions = snap.stats.pendingActions,
            failedActions = snap.stats.failedActions,
            isDeveloperMode = p.core.isDeveloperMode,
            isVerboseLogging = p.core.nebulaLogLevel == NebulaLogLevel.DEBUG,
            isStartOnBoot = p.core.isStartOnBootEnabled,
            firewallRules = firewallLines(nebulaConfig.requiredRules(msgPort), nebula.firewall),
        )
    }

    fun refresh() {
        manual.tryEmit(Unit)
    }

    fun toggleDeveloperMode() = prefs.update { it.copy(core = it.core.copy(isDeveloperMode = !it.core.isDeveloperMode)) }

    fun toggleStartOnBoot() = prefs.update { it.copy(core = it.core.copy(isStartOnBootEnabled = !it.core.isStartOnBootEnabled)) }

    fun toggleVerboseLogging() {
        prefs.update { it.copy(core = it.core.copy(nebulaLogLevel = if (it.core.nebulaLogLevel == NebulaLogLevel.DEBUG) NebulaLogLevel.INFO else NebulaLogLevel.DEBUG)) }
        val profile = runtime.profile.value ?: return
        viewModelScope.launch {
            try {
                runtime.reloadTunnelConfig(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addWarning("Log level saved, but the running tunnel could not reload it (${e.userMessage()}): it applies the next time the tunnel connects.")
            }
        }
    }

    fun reconnectTunnel() {
        val profile = runtime.profile.value ?: return
        viewModelScope.launch {
            runtime.stopVpn()
            notices.reporting("Couldn't reconnect") {
                notices.withLoading("Reconnecting the nebula tunnel…") {
                    delay(RESTART_GAP_MS)
                    runtime.ensureVpn(profile)
                }
            }
        }
    }

    fun openDiagnostics() = navigator.push(Diagnostics)
    fun openLighthouse() = navigator.push(Lighthouse)
    fun openPeer(ip: String) = navigator.push(Contact(ip))
    fun goBack() = navigator.pop()

    companion object {
        /** nebula needs a moment to release the UDP socket before a fresh start binds it */
        const val RESTART_GAP_MS = 800L
    }
}

/** The rules the tunnel runs with, as the renderer applies them: the app's own first, then the user's. */
internal fun firewallLines(required: RequiredRules, firewall: NebulaFirewall): List<String> {
    fun proto(p: FirewallProto) = p.name.lowercase()
    fun user(direction: String, peer: String, r: FirewallRule) = "$direction ${proto(r.proto)} ${r.port}  $peer ${r.match.name.lowercase()} ${r.value}"
    return required.inbound.map { "inbound ${proto(it.proto)} ${it.port}  from any  (${it.reason})" } +
        required.outbound.map { "outbound ${proto(it.proto)} ${it.port}  to any  (${it.reason})" } +
        firewall.inbound.map { user("inbound", "from", it) } +
        firewall.outbound.map { user("outbound", "to", it) }
}
