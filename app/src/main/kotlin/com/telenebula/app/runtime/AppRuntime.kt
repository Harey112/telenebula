package com.telenebula.app.runtime

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.platform.Haptics
import com.telenebula.app.platform.HostKey
import com.telenebula.app.platform.IdentityStore
import com.telenebula.app.platform.ProfileLoad
import com.telenebula.app.platform.BootStart
import com.telenebula.app.platform.ForegroundTracker
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.SystemNightMode
import com.telenebula.app.platform.userMessage
import com.telenebula.app.model.CertExpiryLevel
import com.telenebula.calls.CallEngine
import com.telenebula.core.CoreClient
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.model.Profile
import com.telenebula.core.nebula.NebulaConfigRepository
import com.telenebula.core.notify.MessageNotificationRouter
import com.telenebula.vpn.NebulaVpnController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RuntimeState {
    data object Booting : RuntimeState
    data object NeedsSetup : RuntimeState
    data class Ready(val profile: Profile) : RuntimeState
}

/** Navigation the runtime asks for from outside any screen. */
enum class NavRequest { RENEW_CERTIFICATE }

/**
 * Boots everything a provisioned device needs — store, prefs, profile, messenger, background
 * service, tunnel, call engine — in the same order as the RN app, owned by the Application so
 * a sticky service restart brings the core back without an Activity.
 */
class AppRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val core: CoreClient,
    private val vpn: NebulaVpnController,
    private val identity: IdentityStore,
    private val prefs: PrefsRepository,
    private val notices: NoticeCenter,
    private val gateway: ActivityGateway,
    private val notificationRouter: MessageNotificationRouter,
    private val nebulaConfig: NebulaConfigRepository,
    private val callEngine: CallEngine,
    private val haptics: Haptics,
    private val appLock: AppLock,
    private val nightMode: SystemNightMode,
    private val foreground: ForegroundTracker,
    private val updateMonitor: UpdateMonitor,
    private val isChatOpen: (String) -> Boolean,
    private val bootStart: BootStart,
    private val appVersion: String,
) {
    private val app = context.applicationContext

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Booting)
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    /** The provisioned identity, or null before setup. */
    val profile: StateFlow<Profile?> = mutableState.map { (it as? RuntimeState.Ready)?.profile }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val tunnelRunning: StateFlow<Boolean> = vpn.state.map { it.running }.stateIn(scope, SharingStarted.Eagerly, vpn.isRunning)

    private val isStarting = MutableStateFlow(false)

    /** Off, starting (consent or nebula start in flight) or running; the tunnel itself only knows running. */
    val tunnelPhase: StateFlow<TunnelPhase> = combine(tunnelRunning, isStarting) { running, starting ->
        when {
            running -> TunnelPhase.RUNNING
            starting -> TunnelPhase.STARTING
            else -> TunnelPhase.OFF
        }
    }.stateIn(scope, SharingStarted.Eagerly, if (vpn.isRunning) TunnelPhase.RUNNING else TunnelPhase.OFF)

    private val navFlow = MutableSharedFlow<NavRequest>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val navigationRequests: SharedFlow<NavRequest> = navFlow.asSharedFlow()

    private val isBooted = AtomicBoolean(false)
    private val isSubscribed = AtomicBoolean(false)
    private val parkedVpnConsent = AtomicReference<Profile?>(null)
    private var bootJob: Job? = null
    private var lastStart: Job? = null

    /** the user switched the tunnel off; a tunnel that fell over on its own is brought back, this one is not */
    private val isUserStopped = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectJob: Job? = null

    /** Idempotent; called from Application.onCreate. */
    fun boot() {
        if (!isBooted.compareAndSet(false, true)) return
        bootJob = scope.launch {
            core.openStore()
            val loaded = prefs.load()
            prefs.loadFailure?.let { notices.addWarning("Settings could not be read: $it. The defaults are in use.") }
            launch { prefs.prefs.map { it.app.themeMode }.distinctUntilChanged().collect(nightMode::apply) }
            launch { prefs.prefs.map { it.core.isStartOnBootEnabled }.distinctUntilChanged().collect(bootStart::setEnabled) }
            launch { shareOnline() }
            updateMonitor.start()
            appLock.arm()
            val profile = when (val load = identity.loadProfile()) {
                ProfileLoad.None -> null
                is ProfileLoad.Corrupt -> {
                    notices.addError("Your profile could not be read: ${load.cause}. Set up again to continue.")
                    null
                }
                is ProfileLoad.Loaded -> load.profile
            }
            mutableState.value = if (profile == null) RuntimeState.NeedsSetup else RuntimeState.Ready(profile)
            if (profile == null) {
                // a boot receiver may have started the service before there was anything to serve
                core.stopBackgroundService()
                return@launch
            }
            // the UI is up now; network start continues here and reports through notices
            startRuntime(profile)
            if (loaded.core.autoCleanOrphans) core.clearOrphanAttachments()
            val expiry = CertInspector.expiryStatus(profile.certNotAfter)
            if (expiry.level != CertExpiryLevel.OK) {
                notices.setPrompt(
                    Prompt(
                        message = "Certificate needs attention: ${expiry.text}. Renew it now?",
                        rightLabel = "Renew",
                        onRight = { navFlow.tryEmit(NavRequest.RENEW_CERTIFICATE) },
                    ),
                )
            }
        }
    }

    /**
     * After setup, a certificate renewal or a backup import: (re)starts the messenger and tunnel.
     * Runs on the app scope: publishing [RuntimeState.Ready] replaces the caller's screen, which
     * would cancel a ViewModel-owned coroutine while the VPN consent dialog is still open.
     */
    fun startRuntime(profile: Profile): Job = scope.launch {
        mutableState.value = RuntimeState.Ready(profile)
        subscribeOnce()
        val current = prefs.prefs.value
        try {
            core.start(profile, current, appVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notices.addError("Couldn't start messaging: ${e.userMessage()}")
            return@launch
        }
        // the foreground service is what keeps the process, and with it the tunnel, alive in the background
        gateway.requestPostNotifications()
        core.startBackgroundService()
        connectTunnel(profile)
    }.also { lastStart = it }

    /**
     * Boot or an update started the process. The service is what keeps it alive while [boot]
     * finishes, and these are the two broadcasts the system lets start one from the background —
     * so it is started here and now, inside that allowance, and nothing is waited for.
     */
    fun onSystemStart() {
        core.startBackgroundService()
    }

    /** Our pong says "online" only while a screen is showing and the Status setting allows it; a pause ends on time. */
    private suspend fun shareOnline() {
        prefs.prefs.map { it.core.presence }.distinctUntilChanged().collectLatest { p ->
            foreground.isForeground.collectLatest { isShowing ->
                val now = System.currentTimeMillis()
                core.setOnline(isShowing && p.isSharingAt(now))
                if (isShowing && p.isShared && p.pausedUntil > now) {
                    delay(p.pausedUntil - now)
                    core.setOnline(true)
                }
            }
        }
    }

    /** The chat on screen gets no notification, so this stands in for it; every other chat's notification vibrates on its own. */
    private fun buzzInApp(ip: String, isMuted: Boolean) {
        if (!isMuted && isChatOpen(ip) && prefs.prefs.value.core.notifications.inApp.vibrate) haptics.vibrateShort()
    }

    /** Brings the tunnel up and reports a denial or a failure; a parked consent request stays silent. */
    private suspend fun connectTunnel(profile: Profile) {
        try {
            if (!ensureVpn(profile) && parkedVpnConsent.get() == null) {
                notices.addWarning("VPN permission denied: Turn the tunnel on from Settings once you allow it.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notices.addError("Couldn't connect to the nebula network: ${e.userMessage()}")
        }
    }

    /**
     * Ensures the tunnel is up. False when the user denied VPN permission, or when no Activity is
     * attached yet (the request is parked and retried from [onActivityAttached]).
     */
    suspend fun ensureVpn(profile: Profile): Boolean {
        isUserStopped.set(false)
        if (vpn.isRunning) {
            core.setTunnelState(true)
            return true
        }
        isStarting.value = true
        try {
            return startVpn(profile)
        } finally {
            isStarting.value = false
        }
    }

    private suspend fun startVpn(profile: Profile): Boolean {
        val consent = vpn.prepareIntent()
        if (consent != null) {
            if (!gateway.hasActivity) {
                parkedVpnConsent.set(profile)
                return false
            }
            if (!gateway.requestConsent(consent)) return false
        }
        val key = when (val hostKey = identity.loadHostKey()) {
            HostKey.Missing -> throw IllegalStateException("Host key is missing — reset the app and set up again")
            is HostKey.Unreadable -> throw IllegalStateException("Host key can't be decrypted (${hostKey.cause}) — reset the app and set up again")
            is HostKey.Available -> hostKey.pem
        }
        val site = nebulaConfig.buildSite(profile, prefs.prefs.value.core.nebulaLogLevel)
        vpn.start(site.configJson, key, site.networks, site.routes, site.mtu)
        return true
    }

    /** Applies lighthouse/log-level changes to the running tunnel without dropping it. */
    suspend fun reloadTunnelConfig(profile: Profile) {
        if (!vpn.isRunning) return
        val key = (identity.loadHostKey() as? HostKey.Available)?.pem ?: return
        val site = nebulaConfig.buildSite(profile, prefs.prefs.value.core.nebulaLogLevel)
        vpn.reload(site.configJson, key)
    }

    fun stopVpn() {
        isUserStopped.set(true)
        reconnectJob?.cancel()
        vpn.stop()
        core.setTunnelState(false)
    }

    /** A tunnel that fell over is brought back up the ladder; one the user switched off, or whose consent went, is left alone. */
    private fun scheduleReconnect() {
        if (isUserStopped.get()) return
        val profile = (mutableState.value as? RuntimeState.Ready)?.profile ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(TunnelRetry.delayMs(reconnectAttempts.getAndIncrement()))
            if (vpn.isRunning || isUserStopped.get()) return@launch
            if (vpn.prepareIntent() != null) {
                notices.addWarning("Nebula tunnel: VPN permission was revoked. Turn the tunnel on from the Me tab to allow it again.")
                return@launch
            }
            try {
                ensureVpn(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportTunnelError("Nebula tunnel: ${e.userMessage()}")
                scheduleReconnect()
            }
        }
    }

    /** A profile edit the messenger need not restart for (lighthouse, nebula options). */
    fun updateProfile(profile: Profile) {
        if (mutableState.value is RuntimeState.Ready) mutableState.value = RuntimeState.Ready(profile)
    }

    /** Identity reset: everything down, back to setup. */
    suspend fun stopRuntime() {
        isUserStopped.set(true)
        reconnectJob?.cancel()
        core.stopBackgroundService()
        core.stop()
        vpn.stop()
        mutableState.value = RuntimeState.NeedsSetup
    }

    /** A parked VPN consent request runs as soon as an Activity can host the system dialog. */
    fun onActivityAttached() {
        val profile = parkedVpnConsent.getAndSet(null) ?: return
        scope.launch { connectTunnel(profile) }
    }

    private fun subscribeOnce() {
        if (!isSubscribed.compareAndSet(false, true)) return
        scope.launch {
            vpn.state.collect { s ->
                core.setTunnelState(s.running)
                if (s.running) {
                    reconnectAttempts.set(0)
                    reconnectJob?.cancel()
                }
                // nebula reports a bad config or a failed start after start() returned
                if (!s.running && s.error != null) {
                    reportTunnelError("Nebula tunnel: ${s.error}")
                    scheduleReconnect()
                }
            }
        }
        scope.launch {
            CoreEventBus.events.collect { event ->
                when (event) {
                    is CoreEvent.Signal -> CoreSignalingAdapter.toInboundSignal(event.envelope)
                        ?.let { callEngine.handleSignal(event.fromIp, it) }
                    is CoreEvent.TunnelToggle -> onTunnelToggle()
                    is CoreEvent.MessageReceived -> buzzInApp(event.ip, event.isMuted)
                    is CoreEvent.ReactionReceived -> buzzInApp(event.ip, event.isMuted)
                    else -> Unit
                }
            }
        }
        scope.launch { callEngine.warnings.collect(notices::addWarning) }
        notificationRouter.start()
    }

    /** The notification's tunnel action: off also ends the background service, a stopped tunnel receives nothing. */
    private suspend fun onTunnelToggle() {
        val profile = profile.value ?: return
        if (tunnelRunning.value) {
            stopVpn()
            core.stopBackgroundService()
        } else {
            try {
                ensureVpn(profile)
            } catch (e: Exception) {
                toast("Couldn't start the tunnel: ${e.userMessage()}")
            }
        }
    }

    private fun isForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** In the app the error card; from the notification (app not in front) an Android toast. */
    private suspend fun reportTunnelError(message: String) {
        if (isForeground()) notices.addError(message) else toast(message)
    }

    private suspend fun toast(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}

enum class TunnelPhase { OFF, STARTING, RUNNING }
