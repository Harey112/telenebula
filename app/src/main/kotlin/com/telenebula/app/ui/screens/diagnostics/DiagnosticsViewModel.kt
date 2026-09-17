package com.telenebula.app.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.BuildConfig
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.FileIo
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.calls.CallEngine
import com.telenebula.core.CoreClient
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.CoreJson
import com.telenebula.core.model.LighthouseEntry
import com.telenebula.core.model.NetworkStats
import com.telenebula.core.model.StorageStats
import com.telenebula.vpn.NebulaVpnController
import com.telenebula.vpn.model.HostmapEntry
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class PingResult(val ip: String, val label: String, val rttMs: Long?, val isRunning: Boolean)

data class DiagnosticsUiState(
    val results: List<PingResult> = emptyList(),
    val isTesting: Boolean = false,
    val isExporting: Boolean = false,
    val lighthouseStatus: String = "",
    val retryFailedCount: Int = 0,
    /** actions waiting across every peer, and how many peers are holding them */
    val queuedCount: Int = 0,
    val queuedPeers: Int = 0,
    val logTail: String = "",
    /** the last call's connection trail, newest line last */
    val lastCallTrail: List<String> = emptyList(),
)

private class Derived(val lighthouseStatus: String, val retryFailedCount: Int, val queuedCount: Int, val queuedPeers: Int)
private data class Work(val results: List<PingResult> = emptyList(), val isTesting: Boolean = false, val isExporting: Boolean = false)

class DiagnosticsViewModel(
    private val core: CoreClient,
    private val queues: PeerQueueStore,
    private val vpn: NebulaVpnController,
    private val runtime: AppRuntime,
    private val callEngine: CallEngine,
    private val attachments: AttachmentStore,
    private val openWith: OpenWith,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private val work = MutableStateFlow(Work())
    private val logTail = MutableStateFlow("")
    private val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val derived = merge(manual, runtime.tunnelRunning, core.storeChanges())
        .mapLatest {
            val isRunning = runtime.tunnelRunning.value
            val status = when {
                !isRunning -> "Tunnel off"
                vpn.listHostmap(lighthouseIps()).any { it.isLighthouse } -> "Reachable (tunnel established)"
                else -> "Not reached"
            }
            val stats = core.networkStats()
            Derived(status, stats.failedActions, stats.pendingActions, queues.queues.value.size)
        }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<DiagnosticsUiState> = combine(work, derived, logTail, callEngine.diagnostics) { w, d, log, trail ->
        DiagnosticsUiState(
            w.results, w.isTesting, w.isExporting, d.lighthouseStatus, d.retryFailedCount,
            d.queuedCount, d.queuedPeers, log, trail,
        )
    }.uiState(viewModelScope, DiagnosticsUiState(lastCallTrail = callEngine.diagnostics.value))

    init {
        refreshLog()
    }

    private fun lighthouseIps(): List<String> = runtime.profile.value?.lighthouses?.map { it.nebulaIp } ?: emptyList()

    fun runPeerTest() {
        if (work.value.isTesting) return
        viewModelScope.launch {
            val contacts = core.readContacts().filter { !it.isBlocked }
            work.update { it.copy(results = contacts.map { c -> PingResult(c.ip, ContactLabels.chatLabel(c), null, isRunning = true) }, isTesting = true) }
            notices.setLoading("0 of ${contacts.size} contacts")
            try {
                contacts.forEachIndexed { index, contact ->
                    val rtt = core.pingPeer(contact.ip)
                    notices.setLoading("${index + 1} of ${contacts.size} contacts")
                    work.update { w -> w.copy(results = w.results.map { r -> if (r.ip == contact.ip) PingResult(r.ip, r.label, rtt, isRunning = false) else r }) }
                }
            } finally {
                notices.setLoading(null)
                work.update { it.copy(isTesting = false) }
                manual.tryEmit(Unit)
            }
        }
    }

    /**
     * Try every peer that is holding something, now. Nothing is retried per action any more, so
     * this is a sweep over peers rather than over a list of failures.
     */
    fun sendAllQueuedNow() {
        viewModelScope.launch {
            val requeued = core.retryFailedActions()
            val peers = queues.queues.value.keys.toList()
            for (ip in peers) core.drainNow(ip)
            notices.setSuccess(
                when {
                    requeued > 0 -> "Retrying $requeued refused action(s) now, with everything else still queued."
                    peers.isEmpty() -> "Nothing queued: every peer is up to date."
                    else -> "Sending now: trying ${peers.size} peer(s) holding queued actions."
                },
            )
            manual.tryEmit(Unit)
        }
    }

    fun exportDiagnostics() {
        if (work.value.isExporting) return
        work.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                val file = notices.withLoading("Collecting versions, hostmap, stats and the log tail…") {
                    val report = buildReport()
                    withContext(Dispatchers.IO) {
                        attachments.cacheFile("telenebula-diagnostics.json").also { FileIo.writeAtomic(it, PRETTY.encodeToString(JsonObject.serializer(), report)) }
                    }
                }
                openWith.shareFile(file, "application/json")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Export failed: ${e.userMessage()}")
            } finally {
                work.update { it.copy(isExporting = false) }
            }
        }
    }

    private suspend fun buildReport(): JsonObject {
        val profile = runtime.profile.value
        val lighthouseIps = lighthouseIps()
        return buildJsonObject {
            put("exportedAt", Instant.now().toString())
            put("app", BuildConfig.VERSION_NAME)
            put("core", core.coreVersion())
            put(
                "node",
                if (profile == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("name", profile.certName)
                        put("overlayIp", profile.overlayIp)
                        put("networks", CoreJson.encodeToJsonElement(ListSerializer(String.serializer()), profile.networks))
                        put("lighthouses", CoreJson.encodeToJsonElement(ListSerializer(LighthouseEntry.serializer()), profile.lighthouses))
                        put("fingerprint", profile.certFingerprint)
                    }
                },
            )
            put("tunnel", runtime.tunnelRunning.value)
            put("network", CoreJson.encodeToJsonElement(NetworkStats.serializer(), core.networkStats()))
            put("storage", CoreJson.encodeToJsonElement(StorageStats.serializer(), core.storageStats()))
            put("hostmap", vpn.listHostmap(lighthouseIps).toJson())
            put("pendingHandshakes", vpn.listHostmap(lighthouseIps, pending = true).toJson())
            put(
                "peerTest",
                buildJsonArray {
                    for (r in work.value.results) {
                        add(buildJsonObject { put("ip", r.ip); put("label", r.label); put("rttMs", r.rttMs) })
                    }
                },
            )
            put("lastCall", JsonArray(callEngine.diagnostics.value.map(::JsonPrimitive)))
            put("nebulaLogTail", vpn.readLog(REPORT_LOG_BYTES))
        }
    }

    fun refreshLog() {
        viewModelScope.launch { logTail.value = vpn.readLog(VIEW_LOG_BYTES) }
    }

    fun clearLog() {
        viewModelScope.launch {
            vpn.clearLog()
            logTail.value = vpn.readLog(VIEW_LOG_BYTES)
        }
    }

    fun goBack() = navigator.pop()

    private companion object {
        const val VIEW_LOG_BYTES = 24 * 1024
        const val REPORT_LOG_BYTES = 32 * 1024
        val PRETTY = kotlinx.serialization.json.Json(CoreJson) { prettyPrint = true }

        fun List<HostmapEntry>.toJson(): JsonArray = buildJsonArray {
            for (h in this@toJson) {
                add(
                    buildJsonObject {
                        put("vpnIp", h.vpnIp)
                        put("currentRemote", h.currentRemote)
                        put("remoteAddrs", JsonArray(h.remoteAddrs.map(::JsonPrimitive)))
                        put("certName", h.certName)
                        put("certFingerprint", h.certFingerprint)
                        put("isRelayed", h.isRelayed)
                        put("isLighthouse", h.isLighthouse)
                    },
                )
            }
        }
    }
}
