package com.telenebula.web.net

import com.telenebula.web.wire.ClientFrame
import com.telenebula.web.wire.DexJson
import com.telenebula.web.wire.ServerFrame
import kotlinx.browser.window
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.CloseEvent
import org.w3c.dom.MessageEvent

enum class SocketStatus { CONNECTING, CONNECTED, RECONNECTING, UNAUTHORIZED, LIMIT }

/**
 * One socket to the phone with reconnect. Frames the phone sends are full snapshots, so a
 * reconnect only needs [onOpen] to resubscribe what the browser is looking at.
 */
class Socket(
    private val onFrame: (ServerFrame) -> Unit,
    private val onStatus: (SocketStatus) -> Unit,
    private val onOpen: () -> Unit,
) {
    private var ws: WebSocket? = null
    private var attempt = 0
    private var reconnectTimer: Int? = null
    private var pingTimer: Int? = null
    private var isStopped = false
    private var status = SocketStatus.CONNECTING

    fun start() {
        isStopped = false
        attempt = 0
        connect()
    }

    fun stop() {
        isStopped = true
        reconnectTimer?.let(window::clearTimeout)
        reconnectTimer = null
        stopPing()
        ws?.let { closeQuietly(it) }
        ws = null
    }

    val isConnected: Boolean get() = ws?.readyState == WebSocket.OPEN

    fun send(frame: ClientFrame): Boolean {
        val socket = ws ?: return false
        if (socket.readyState != WebSocket.OPEN) return false
        return try {
            socket.send(DexJson.encodeToString(ClientFrame.serializer(), frame))
            true
        } catch (e: Throwable) {
            console.warn("dex socket send failed", e)
            false
        }
    }

    private fun connect() {
        if (isStopped) return
        val scheme = if (window.location.protocol == "https:") "wss" else "ws"
        val socket = try {
            WebSocket("$scheme://${window.location.host}/ws")
        } catch (e: Throwable) {
            scheduleReconnect()
            return
        }
        ws = socket
        setStatus(if (attempt == 0) SocketStatus.CONNECTING else SocketStatus.RECONNECTING)
        socket.onopen = {
            attempt = 0
            setStatus(SocketStatus.CONNECTED)
            startPing()
            onOpen()
        }
        socket.onmessage = { e: MessageEvent -> receive(e) }
        socket.onerror = { _: Event -> Unit }
        socket.onclose = { raw: Event ->
            val e = raw.unsafeCast<CloseEvent>()
            stopPing()
            if (ws === socket) ws = null
            when {
                isStopped -> Unit
                // the server refuses the upgrade before any frame: the browser sees only a close
                e.code.toInt() == 1008 || e.reason == "unauthorized" -> setStatus(SocketStatus.UNAUTHORIZED)
                e.code.toInt() == 1013 && e.reason.contains("limit", ignoreCase = true) -> {
                    setStatus(SocketStatus.LIMIT)
                    scheduleReconnect()
                }
                else -> scheduleReconnect()
            }
        }
    }

    private fun receive(e: MessageEvent) {
        val text = e.data as? String ?: return
        val frame = try {
            DexJson.decodeFromString(ServerFrame.serializer(), text)
        } catch (t: Throwable) {
            console.warn("dex frame ignored", t.message)
            return
        }
        try {
            onFrame(frame)
        } catch (t: Throwable) {
            console.error("dex frame handler failed", t)
        }
    }

    private fun scheduleReconnect() {
        if (isStopped || reconnectTimer != null) return
        if (status != SocketStatus.LIMIT) setStatus(SocketStatus.RECONNECTING)
        val delay = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
        attempt += 1
        reconnectTimer = window.setTimeout({
            reconnectTimer = null
            connect()
        }, delay)
    }

    /** A failed HTTP-level handshake surfaces as a generic close; the app re-checks the session on repeated failures. */
    val failedAttempts: Int get() = attempt

    private fun startPing() {
        stopPing()
        pingTimer = window.setInterval({ send(ClientFrame.Ping) }, PING_MS)
    }

    private fun stopPing() {
        pingTimer?.let(window::clearInterval)
        pingTimer = null
    }

    private fun setStatus(next: SocketStatus) {
        if (status == next) return
        status = next
        onStatus(next)
    }

    private fun closeQuietly(socket: WebSocket) {
        try {
            socket.onclose = null
            socket.close()
        } catch (e: Throwable) {
            // already closed
        }
    }

    private companion object {
        val BACKOFF_MS = intArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        const val PING_MS = 25_000
    }
}
