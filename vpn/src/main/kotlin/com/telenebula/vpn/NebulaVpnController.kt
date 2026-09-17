package com.telenebula.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.telenebula.vpn.model.HostmapEntry
import com.telenebula.vpn.model.NebulaTunnelState
import com.telenebula.vpn.model.ParsedCertEntry
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The app's only entry point to the nebula tunnel: certificate helpers from the gomobile
 * bindings, the VpnService start/stop/reload intents, and diagnostics read from the live
 * instance. Everything is primitive-in/primitive-out so this module has no dependency on
 * the core models.
 */
class NebulaVpnController(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    val state: StateFlow<NebulaTunnelState> get() = NebulaState.state
    val isRunning: Boolean get() = NebulaState.running

    /** Epoch ms when the tunnel came up, 0 while it is down. */
    fun startedAt(): Long = NebulaState.startedAt

    fun uptimeMs(): Long {
        val since = NebulaState.startedAt
        return if (since > 0) System.currentTimeMillis() - since else 0
    }

    /** PEM (host or CA certificate) → parsed entries. Throws [NebulaVpnException] on a bad PEM. */
    suspend fun parseCerts(pem: String): List<ParsedCertEntry> = withContext(io) {
        val raw = nebulaCall("Could not read certificate") { mobileNebula.MobileNebula.parseCerts(pem) }
        json.decodeFromString(raw)
    }

    /** True when the host certificate and private key belong together; throws with the reason otherwise. */
    suspend fun verifyCertAndKey(certPem: String, keyPem: String): Boolean = withContext(io) {
        nebulaCall("Certificate and key do not match") { mobileNebula.MobileNebula.verifyCertAndKey(certPem, keyPem) }
    }

    /** Established tunnels (pending = handshakes still in progress). Empty while nebula is down. */
    suspend fun listHostmap(lighthouseIps: Collection<String>, pending: Boolean = false): List<HostmapEntry> = withContext(io) {
        val instance = NebulaState.instance ?: return@withContext emptyList()
        val raw = runCatching { instance.listHostmap(pending) }.getOrNull() ?: return@withContext emptyList()
        HostmapJson.parseList(raw, lighthouseSet(lighthouseIps))
    }

    suspend fun hostInfo(vpnIp: String, lighthouseIps: Collection<String>): HostmapEntry? = withContext(io) {
        val instance = NebulaState.instance ?: return@withContext null
        val raw = runCatching { instance.getHostInfoByVpnIp(vpnIp, false) }.getOrNull() ?: return@withContext null
        HostmapJson.parseOne(raw, lighthouseSet(lighthouseIps))
    }

    /** Drops the tunnel to one peer; nebula re-handshakes on the next packet. */
    suspend fun closeTunnel(vpnIp: String): Boolean = withContext(io) {
        NebulaState.instance?.let { runCatching { it.closeTunnel(vpnIp) }.getOrDefault(false) } ?: false
    }

    /** Tail of nebula's own log file (last [maxBytes]). */
    suspend fun readLog(maxBytes: Int = DEFAULT_LOG_TAIL_BYTES): String = withContext(io) {
        val file = logFile()
        if (!file.isFile) return@withContext ""
        val length = file.length()
        val start = (length - maxBytes).coerceAtLeast(0)
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                LogRedaction.redact(String(bytes, Charsets.UTF_8))
            }
        }.getOrDefault("")
    }

    suspend fun clearLog() = withContext(io) {
        runCatching { logFile().writeText("") }
        Unit
    }

    /**
     * Live config reload (log level, lighthouses…) without dropping the tunnel. Same site JSON
     * as [start]; the key is injected natively again.
     */
    suspend fun reload(configJson: String, key: String) = withContext(io) {
        val instance = NebulaState.instance ?: throw NebulaNotRunningException()
        nebulaCall("Reload failed") { instance.reload(configJson, key) }
        Unit
    }

    /** null when the system already granted VPN permission; otherwise the consent Intent to launch. */
    fun prepareIntent(): Intent? = VpnService.prepare(appContext)

    /**
     * Start nebula inside the VpnService. The parameters, key included, never leave the process:
     * the service picks them up from [VpnStartHandoff].
     * @param configJson `{"name","id","rawConfig": "<json-encoded nebula config, no pki.key>"}`
     * @param key host private key PEM (injected natively)
     * @param networks overlay CIDRs from the host certificate
     * @param routes unsafe-route CIDRs to claim in the tun
     */
    fun start(configJson: String, key: String, networks: List<String>, routes: List<String>, mtu: Int) {
        val nonce = VpnStartHandoff.offer(VpnStartRequest(configJson, key, networks, routes, mtu))
        try {
            startService(Intent(appContext, NebulaVpnService::class.java).putExtra(NebulaVpnService.EXTRA_NONCE, nonce))
        } catch (e: NebulaVpnException) {
            VpnStartHandoff.clear()
            throw e
        }
    }

    fun stop() {
        startService(Intent(appContext, NebulaVpnService::class.java).setAction(NebulaVpnService.ACTION_STOP))
    }

    private fun startService(intent: Intent) {
        try {
            appContext.startService(intent)
        } catch (e: IllegalStateException) {
            // background start limits: the app has no visible activity and no foreground service yet
            throw NebulaVpnException("The tunnel can only be started while the app is open", e)
        }
    }

    private fun logFile(): File = NebulaState.logPath?.let(::File) ?: File(appContext.filesDir, LOG_FILE_NAME)

    private inline fun <T> nebulaCall(fallbackMessage: String, block: () -> T): T =
        try {
            block()
        } catch (e: NebulaVpnException) {
            throw e
        } catch (e: Exception) {
            throw NebulaVpnException(e.message?.takeIf { it.isNotBlank() } ?: fallbackMessage, e)
        }

    private companion object {
        const val DEFAULT_LOG_TAIL_BYTES = 64 * 1024
        const val LOG_FILE_NAME = "nebula.log"
    }
}
