package com.telenebula.web.ui

import com.telenebula.web.call.attach
import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexCallPhase
import com.telenebula.web.wire.DexCallState
import com.telenebula.web.wire.DexSeat
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.Date

/** The incoming ring, the call panel this browser owns, or the mirror banner; one of them at a time. */
class CallsView(root: HTMLElement, bannerSlot: HTMLElement, private val actions: Actions) {
    private val ring = div("modal-scrim hidden").also { root.appendChild(it) }
    private val ringName = div("ring-name")
    private val ringKind = div("ring-kind")

    private val panel = div("call-panel hidden").also { root.appendChild(it) }
    val remoteVideo = el("video", "remote-video") { setAttribute("autoplay", ""); setAttribute("playsinline", "") } as HTMLVideoElement
    val localVideo = el("video", "local-video") { setAttribute("autoplay", ""); setAttribute("playsinline", ""); setAttribute("muted", ""); asDynamic().muted = true } as HTMLVideoElement
    private val panelName = div("call-name")
    private val panelStatus = div("call-status")
    private val avatar = div("call-avatar")
    private val muteBtn = button("round", "Mute", { actions.toggleMute() }, Icon.MIC)
    private val camBtn = button("round", "Camera", { actions.toggleCamera() }, Icon.VIDEO_OFF)
    private val endBtn = button("round danger", "End call", { actions.endCall() }, Icon.CALL_END)
    private val moveBtn = button("icon-btn move-phone", "Move call to phone", { actions.moveCallToPhone() }, Icon.SMARTPHONE)

    private val banner = div("call-banner hidden").also { bannerSlot.appendChild(it) }
    private val bannerText = span("call-banner-text")

    private var timer: Int? = null
    private var ringer: Ringer? = null

    init {
        ring.add(
            div("modal ring").add(
                div("ring-avatar").add(svg(Icon.CALL, 28)),
                ringName,
                ringKind,
                div("ring-actions").add(
                    button("round danger", "Decline", { actions.rejectCall() }, Icon.CALL_END),
                    button("round success", "Accept", { actions.acceptCall() }, Icon.CALL),
                ),
            ),
        )
        panel.add(
            remoteVideo,
            div("call-top").add(div("call-heading").add(panelName, panelStatus), moveBtn),
            avatar,
            localVideo,
            div("call-controls").add(muteBtn, camBtn, endBtn),
        )
        banner.add(svg(Icon.CALL, 14), bannerText)
    }

    fun render(prev: AppState, next: AppState) {
        val c = next.call
        val changed = prev.call !== c || prev.clientId != next.clientId
        if (!changed) return
        val isIncoming = c.phase == DexCallPhase.INCOMING
        val isLive = c.phase != DexCallPhase.IDLE && c.phase != DexCallPhase.ENDED && !isIncoming
        val isMine = next.isMySeat && isLive
        val isMirror = isLive && !isMine && (c.seat == DexSeat.PHONE || c.seat == DexSeat.DEX)
        // a call this browser placed rings on the phone side; it is "mine" from the first frame
        val isMineDialing = isLive && c.seat == DexSeat.DEX && c.seatClientId == next.clientId

        ring.toggle("hidden", !isIncoming)
        if (isIncoming) {
            ringName.textContent = c.peer?.name ?: c.peer?.ip.orEmpty()
            ringKind.textContent = if (c.video) "Incoming video call" else "Incoming call"
            if (ringer == null) ringer = Ringer().also { it.start() }
        } else {
            ringer?.stop()
            ringer = null
        }

        panel.toggle("hidden", !(isMine || isMineDialing))
        if (isMine || isMineDialing) {
            panelName.textContent = c.peer?.name ?: c.peer?.ip.orEmpty()
            updateStatus(c)
            val isMoving = c.movingTo != null
            (moveBtn as? org.w3c.dom.HTMLButtonElement)?.disabled = isMoving || c.phase != DexCallPhase.ACTIVE
            moveBtn.title = if (isMoving) "Moving to phone…" else "Move call to phone"
            panel.toggle("video-on", c.remoteCamOn)
            avatar.textContent = (c.peer?.name ?: "?").take(1).uppercase()
            startTimer(c)
            refreshControls()
        } else {
            stopTimer()
        }

        banner.toggle("hidden", !isMirror)
        if (isMirror) {
            updateBanner(c)
            startTimer(c)
        }
        if (!isMirror && !isMine && !isMineDialing) stopTimer()
    }

    fun refreshControls() {
        muteBtn.clear()
        muteBtn.appendChild(svg(if (actions.isMuted) Icon.MIC_OFF else Icon.MIC, 22))
        muteBtn.toggle("on", actions.isMuted)
        muteBtn.setAttribute("aria-label", if (actions.isMuted) "Unmute" else "Mute")
        camBtn.clear()
        camBtn.appendChild(svg(if (actions.isCameraOn) Icon.VIDEO else Icon.VIDEO_OFF, 22))
        camBtn.toggle("on", actions.isCameraOn)
        camBtn.setAttribute("aria-label", if (actions.isCameraOn) "Turn camera off" else "Turn camera on")
        localVideo.toggle("hidden", !actions.isCameraOn)
    }

    fun showLocal(stream: dynamic) {
        localVideo.attach(stream)
        refreshControls()
    }

    fun showRemote(stream: dynamic) = remoteVideo.attach(stream)

    fun clearVideo() {
        remoteVideo.attach(null)
        localVideo.attach(null)
    }

    private fun label(c: DexCallState): String = when (c.phase) {
        DexCallPhase.CONTACTING -> "Contacting…"
        DexCallPhase.RINGING -> "Ringing…"
        DexCallPhase.CONNECTING -> "Connecting…"
        DexCallPhase.ACTIVE -> if (c.startedAt > 0) Format.duration(Date.now().toLong() - c.startedAt) else "00:00"
        else -> ""
    }

    private fun updateStatus(c: DexCallState) {
        panelStatus.textContent = if (c.movingTo != null) "Moving to phone…" else label(c)
    }

    private fun updateBanner(c: DexCallState) {
        val where = if (c.seat == DexSeat.PHONE) "on phone" else "on another Dex client"
        bannerText.textContent = "Call with ${c.peer?.name ?: c.peer?.ip.orEmpty()} · $where · ${label(c)}"
    }

    private fun startTimer(c: DexCallState) {
        stopTimer()
        if (c.phase != DexCallPhase.ACTIVE) return
        timer = window.setInterval({
            updateStatus(c)
            updateBanner(c)
        }, 1_000)
    }

    private fun stopTimer() {
        timer?.let(window::clearInterval)
        timer = null
    }
}

/** Two-tone ring through WebAudio; nothing to download, nothing to autoplay-block except the context itself. */
private class Ringer {
    private var ctx: dynamic = null
    private var timer: Int? = null

    fun start() {
        try {
            ctx = js("new (window.AudioContext || window.webkitAudioContext)()")
        } catch (e: Throwable) {
            return
        }
        beep()
        timer = window.setInterval({ beep() }, 2_500)
    }

    private fun beep() {
        val c = ctx ?: return
        try {
            for (i in 0 until 2) {
                val osc = c.createOscillator()
                val gain = c.createGain()
                osc.type = "sine"
                osc.frequency.value = if (i == 0) 880 else 660
                gain.gain.value = 0.08
                osc.connect(gain)
                gain.connect(c.destination)
                val at = (c.currentTime as Double) + i * 0.45
                osc.start(at)
                osc.stop(at + 0.35)
            }
        } catch (e: Throwable) {
            // the context was closed underneath us
        }
    }

    fun stop() {
        timer?.let(window::clearInterval)
        timer = null
        try {
            ctx?.close()
        } catch (e: Throwable) {
            // already closed
        }
        ctx = null
    }
}
