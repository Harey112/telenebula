package com.telenebula.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal VpnService that runs an embedded nebula instance (gomobile bindings
 * from DefinedNet/mobile_nebula) over the tun fd. Modeled on the official
 * mobile_nebula NebulaVpnService, trimmed to what TeleNebula needs.
 */
class NebulaVpnService : VpnService() {

  companion object {
    const val TAG = "NebulaVpnService"
    const val ACTION_STOP = "com.telenebula.nebulavpn.STOP"
    const val EXTRA_NONCE = "nonce"
  }

  /** Every call into the Go runtime blocks, so all of them run here, one at a time and in order. */
  private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

  @Volatile private var nebula: mobileNebula.Nebula? = null
  private var vpnInterface: ParcelFileDescriptor? = null
  private var callbackRegistered = false
  private var isShutDown = false

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      nebula?.rebind("network change")
    }

    override fun onLost(network: Network) {
      nebula?.rebind("network change")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when {
      intent == null -> stopSelf()
      intent.action == ACTION_STOP -> shutDown(null)
      nebula != null -> NebulaState.emit(true)
      else -> {
        val request = VpnStartHandoff.take(intent.getStringExtra(EXTRA_NONCE))
        worker.launch { if (request == null) shutDownNow("VPN start request expired") else startVpn(request) }
      }
    }
    return START_NOT_STICKY
  }

  private fun startVpn(request: VpnStartRequest) {
    if (nebula != null) return
    isShutDown = false
    val builder = Builder()
      .setMtu(request.mtu)
      .setSession("TeleNebula")
      .allowFamily(OsConstants.AF_INET)
      .allowFamily(OsConstants.AF_INET6)

    try {
      request.networks.forEach { n ->
        val cidr = mobileNebula.MobileNebula.parseCIDR(n)
        builder.addAddress(cidr.address, cidr.prefixLength.toInt())
        builder.addRoute(cidr.maskedAddress, cidr.prefixLength.toInt())
      }
    } catch (e: Exception) {
      shutDownNow("Invalid overlay network: ${e.message}")
      return
    }

    // unsafe routes: nebula forwards them to the gateway node, but Android only
    // hands packets to the tun for prefixes the VPN claims
    try {
      request.routes.forEach { r ->
        val cidr = mobileNebula.MobileNebula.parseCIDR(r)
        builder.addRoute(cidr.maskedAddress, cidr.prefixLength.toInt())
      }
    } catch (e: Exception) {
      shutDownNow("Invalid unsafe route: ${e.message}")
      return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
    }

    // NOTE: unlike the official mobile_nebula app we deliberately do NOT
    // exclude our own package from the VPN — this app itself talks to peers
    // over the overlay (TCP messaging, WebRTC calls). Only the overlay prefix
    // is routed into the tun, so the UDP underlay traffic to lighthouses and
    // peers (public IPs) cannot loop back into the tunnel.

    try {
      val tun = builder.establish()
        ?: throw IllegalStateException("VpnService.establish() returned null (missing permission?)")
      vpnInterface = tun

      val logFile = File(filesDir, "nebula.log").absolutePath
      // Go owns the detached fd from the moment newNebula is called and closes
      // it on failure — never close it here afterwards.
      val n = mobileNebula.MobileNebula.newNebula(request.configJson, request.key, logFile, tun.detachFd().toLong())
      nebula = n
      NebulaState.instance = n
      NebulaState.startedAt = System.currentTimeMillis()
      NebulaState.logPath = logFile

      n.start(object : mobileNebula.ExitCallback {
        // fired from a Go thread when nebula dies on its own, never on a clean stop
        override fun onExit(message: String?) {
          shutDown(message ?: "Nebula exited unexpectedly", only = n)
        }
      })
    } catch (e: Exception) {
      nebula = null
      NebulaState.instance = null
      try {
        vpnInterface?.close()
      } catch (_: Exception) {}
      vpnInterface = null
      shutDownNow(e.message ?: e.toString())
      return
    }

    registerNetworkCallback()
    NebulaState.emit(true)
    Log.i(TAG, "Nebula started")
  }

  /** [only] keeps a stale exit callback from a dying session from tearing down a newer one. */
  private fun shutDown(error: String?, only: mobileNebula.Nebula? = null) {
    worker.launch {
      if (only != null && nebula !== only) return@launch
      shutDownNow(error)
    }
  }

  private fun shutDownNow(error: String?) {
    if (isShutDown) return
    isShutDown = true
    if (error != null) Log.e(TAG, error)
    unregisterNetworkCallback()
    nebula?.stop()
    nebula = null
    NebulaState.instance = null
    NebulaState.startedAt = 0
    NebulaState.logPath = null
    vpnInterface = null
    NebulaState.emit(false, error)
    stopSelf()
  }

  // Detects underlay changes (wifi <-> cell) and rebinds the udp socket
  private fun registerNetworkCallback() {
    if (callbackRegistered) return
    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
    cm.registerNetworkCallback(request, networkCallback)
    callbackRegistered = true
  }

  private fun unregisterNetworkCallback() {
    if (!callbackRegistered) return
    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    try {
      cm.unregisterNetworkCallback(networkCallback)
    } catch (_: Exception) {}
    callbackRegistered = false
  }

  override fun onRevoke() {
    shutDown("VPN permission revoked by the system")
    super.onRevoke()
  }

  override fun onDestroy() {
    shutDown(null)
    super.onDestroy()
  }
}
