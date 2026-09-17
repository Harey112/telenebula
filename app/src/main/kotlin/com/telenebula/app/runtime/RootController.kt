package com.telenebula.app.runtime

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.telenebula.app.LaunchRequests
import com.telenebula.calls.system.FloatingCallState
import com.telenebula.calls.system.FloatingCallWindow
import com.telenebula.app.nav.ChatsTab
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.RenewCertificate
import com.telenebula.app.nav.Settings
import com.telenebula.app.nav.Tab
import com.telenebula.app.nav.Updates
import com.telenebula.app.nav.Setup
import com.telenebula.app.nav.TabKey
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.deniedCallPermission
import com.telenebula.calls.CallAction
import com.telenebula.calls.CallEngine
import com.telenebula.calls.CallPhase
import com.telenebula.calls.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Root-level reactions that no screen owns: the entry gate, surfacing the call screen, the tab
 * badge, deep links from notifications, and requests the runtime makes from outside a screen.
 * Runs on the main thread because it drives the navigator (Compose snapshot state).
 */
class RootController(
    context: Context,
    private val scope: CoroutineScope,
    private val runtime: AppRuntime,
    private val navigator: Navigator,
    private val callEngine: CallEngine,
    private val presence: CallPresence,
    private val appLock: AppLock,
    private val gateway: ActivityGateway,
    private val notices: NoticeCenter,
) {
    private val app = context.applicationContext
    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)

    private var started = false
    private var autoLeaveJob: Job? = null

    fun start(launch: LaunchRequests) {
        if (started) return
        started = true
        main.launch {
            runtime.state.collect { state ->
                when (state) {
                    RuntimeState.Booting -> Unit
                    RuntimeState.NeedsSetup -> if (navigator.backStack.firstOrNull() != Setup) navigator.replaceAll(Setup)
                    is RuntimeState.Ready -> if (navigator.backStack.firstOrNull() !is TabKey) {
                        navigator.replaceAll(ChatsTab)
                        if (!appLock.isLocked.value) {
                            launch.pendingChatIp.value?.let { ip ->
                                launch.pendingChatIp.value = null
                                navigator.openChat(ip)
                            }
                        }
                    }
                }
            }
        }
        // an incoming ring, or a call answered from its notification, surfaces the call screen once
        main.launch {
            var previous: CallPhase? = null
            callEngine.state.collect { state ->
                val phase = (state as? CallState.Live)?.phase
                when {
                    phase == CallPhase.INCOMING -> navigator.openCall()
                    phase == CallPhase.CONNECTING && previous == CallPhase.INCOMING -> navigator.openCall()
                    state is CallState.Ended && state.reason == null -> scheduleAutoLeave()
                    state is CallState.Idle -> autoLeaveJob?.cancel()
                }
                previous = phase
            }
        }
        main.launch { callEngine.openRequests.collect { navigator.openCall() } }
        main.launch {
            launch.openCall.collect { wanted ->
                if (wanted) {
                    launch.openCall.value = false
                    if (callEngine.state.value is CallState.Live) navigator.openCall()
                }
            }
        }
        main.launch {
            launch.answerCall.collect { wanted ->
                if (wanted) {
                    launch.answerCall.value = false
                    val incoming = (callEngine.state.value as? CallState.Live)?.takeIf { it.phase == CallPhase.INCOMING } ?: return@collect
                    // the app is in front now, so the permission can be asked for like from the Accept button
                    navigator.openCall()
                    val denied = gateway.deniedCallPermission(incoming.session.video)
                    if (denied != null) {
                        notices.addWarning(denied.needed("answer a call"))
                        callEngine.reject()
                    } else {
                        callEngine.onCallAction(CallAction.ANSWER)
                    }
                }
            }
        }
        // no distinctUntilChanged: the cleared null never reaches it, so a second tap on the same
        // chat would read as a repeat. A link that arrives while locked waits for the unlock.
        main.launch {
            combine(launch.pendingChatIp, runtime.state, appLock.isLocked) { ip, state, isLocked ->
                ip?.takeIf { state is RuntimeState.Ready && !isLocked }
            }
                .filterNotNull()
                .collect { ip ->
                    launch.pendingChatIp.value = null
                    navigator.openChat(ip)
                }
        }
        // the update notification: waits for the tabs and the unlock like a chat link does
        main.launch {
            combine(launch.openUpdates, runtime.state, appLock.isLocked) { wanted, state, isLocked -> wanted && state is RuntimeState.Ready && !isLocked }
                .filter { it }
                .collect {
                    launch.openUpdates.value = false
                    navigator.switchTab(Tab.ME)
                    navigator.push(Settings)
                    navigator.push(Updates)
                }
        }
        // the native draw-over-apps window: shown whenever a video-capable call is live and the call
        // screen is not in front of the user — another route, or the app itself in the background
        main.launch {
            var wasShowing = false
            combine(presence.state, launch.overlayPermitted, ProcessLifecycleOwner.get().lifecycle.currentStateFlow, appLock.isLocked) { p, permitted, lifecycle, isLocked ->
                val isResumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
                if (permitted && !isLocked && p.isLive && p.hasVideo && (p.isOffCallScreen || !isResumed)) p else null
            }.collect { p ->
                val live = p?.live
                if (p == null || live == null) {
                    if (wasShowing) {
                        wasShowing = false
                        FloatingCallWindow.hide()
                    }
                    return@collect
                }
                wasShowing = true
                FloatingCallWindow.show(
                    app,
                    callEngine.eglContext,
                    FloatingCallState(
                        remoteTrack = live.session.remoteVideo,
                        localTrack = live.session.localVideo,
                        remoteCamOn = live.session.remoteCamOn,
                        localCamOn = !live.session.camOff,
                        isLocalFrontCamera = live.session.isFrontCamera,
                        peerName = p.peerName,
                        status = p.statusLabel,
                    ),
                )
            }
        }
        main.launch {
            runtime.navigationRequests.collect { request ->
                when (request) {
                    NavRequest.RENEW_CERTIFICATE -> navigator.push(RenewCertificate)
                }
            }
        }
    }

    /** An ended call without a reason leaves the call screen after a beat, as the RN app did. */
    private fun scheduleAutoLeave() {
        autoLeaveJob?.cancel()
        autoLeaveJob = main.launch {
            delay(AUTO_LEAVE_MS)
            if (callEngine.state.value is CallState.Ended) {
                navigator.closeCall()
                callEngine.acknowledgeEnded()
            }
        }
    }

    private companion object {
        const val AUTO_LEAVE_MS = 1_200L
    }
}
