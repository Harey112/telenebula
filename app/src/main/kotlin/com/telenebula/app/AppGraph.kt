package com.telenebula.app

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nebula.NebulaDraftStore
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.AvatarBitmaps
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.platform.EmojiCatalog
import com.telenebula.app.platform.Haptics
import com.telenebula.app.platform.IdentityStore
import com.telenebula.app.platform.KeystoreBox
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.BootStart
import com.telenebula.app.platform.ForegroundTracker
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.SystemNightMode
import com.telenebula.app.platform.UpdateInstaller
import com.telenebula.app.platform.UpdateNotifier
import com.telenebula.app.platform.VoicePlayer
import com.telenebula.app.platform.VoiceRecorder
import com.telenebula.app.platform.UpdateChecker
import com.telenebula.app.runtime.UpdateMonitor
import com.telenebula.app.runtime.ScreenshotPolicy
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.runtime.CallPresence
import com.telenebula.app.runtime.CoreSignalingAdapter
import com.telenebula.app.runtime.DexBackendAdapter
import com.telenebula.app.runtime.DexCallBridge
import com.telenebula.app.runtime.DexController
import com.telenebula.app.platform.WebAssets
import com.telenebula.app.runtime.RootController
import com.telenebula.app.sheets.MediaViewerCenter
import com.telenebula.app.sheets.SheetCenter
import com.telenebula.calls.CallEngine
import com.telenebula.calls.CallPrefs
import com.telenebula.calls.CallsConfig
import com.telenebula.calls.audio.CallAudio
import com.telenebula.calls.media.WebRtcRuntime
import com.telenebula.core.CoreClient
import com.telenebula.core.CoreErrorSink
import com.telenebula.core.CoreJson
import com.telenebula.core.CoreNotificationConfig
import com.telenebula.app.ui.shared.ChatSearchRequests
import com.telenebula.core.PresenceStore
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.TransferProgressStore
import com.telenebula.core.TypingStore
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.nebula.NebulaConfigRepository
import com.telenebula.core.notify.MessageNotificationRouter
import com.telenebula.vpn.NebulaVpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The object graph, built once by the Application. Plain constructor injection: every dependency
 * is visible here, nothing is resolved at runtime.
 */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val notices = NoticeCenter()
    val sheets = SheetCenter()
    val core = CoreClient(app, CoreErrorSink(notices::addError))
    val transfers = TransferProgressStore(CoreEventBus, appScope)
    val typing = TypingStore(CoreEventBus, appScope)
    val peerPresence = PresenceStore(CoreEventBus, appScope)
    val foreground = ForegroundTracker()

    /** what each peer's outbound queue is doing; empty for every peer with nothing waiting */
    val peerQueues = PeerQueueStore(CoreEventBus, appScope)

    /** chat settings asking the chat underneath it to open its search field */
    val chatSearch = ChatSearchRequests()
    val vpn = NebulaVpnController(app)
    val nebulaConfig = NebulaConfigRepository()
    val prefs = PrefsRepository(app, CoreJson, appScope, core::setNotificationPrefs, mirrorReadReceipts = { app, anyProfile -> appScope.launch { core.setSendReadReceipts(app, anyProfile) } })
    val identity = IdentityStore(app, CoreJson, KeystoreBox())
    val gateway = ActivityGateway()
    /** app-scoped: an Activity recreated while backgrounded (e.g. answering a call from its notification) must not lose a pending request */
    val launchRequests = LaunchRequests()
    val certs = CertInspector(vpn)
    val attachments = AttachmentStore(app)
    val viewer = MediaViewerCenter(attachments, notices, appScope)
    val openWith = OpenWith(app)
    val voicePlayer = VoicePlayer(appScope)
    val voiceRecorder = VoiceRecorder(app)
    val haptics = Haptics(app)
    val emojis = EmojiCatalog(app, appScope)
    val updates = UpdateChecker(CoreJson)
    val installer = UpdateInstaller(app, openWith)
    val updateMonitor = UpdateMonitor(prefs, updates, UpdateNotifier(app), appScope, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
    val appLock = AppLock(app, isEnabled = { prefs.prefs.value.core.isAppLockEnabled }, lockAfterSec = { prefs.prefs.value.core.appLockAfterSec })

    val webRtc = WebRtcRuntime(app)
    val dexCalls = DexCallBridge(appScope)
    val callEngine = CallEngine(
        context = app,
        core = CoreSignalingAdapter(core),
        prefs = ::callPrefs,
        audio = CallAudio(app),
        runtime = webRtc,
        remote = dexCalls,
    ).also(dexCalls::attach)

    val runtime = AppRuntime(
        context = app,
        scope = appScope,
        core = core,
        vpn = vpn,
        identity = identity,
        prefs = prefs,
        notices = notices,
        gateway = gateway,
        notificationRouter = MessageNotificationRouter(app, CoreEventBus, appScope, isChatOpen = ::isChatOpen),
        nebulaConfig = nebulaConfig,
        callEngine = callEngine,
        haptics = haptics,
        appLock = appLock,
        nightMode = SystemNightMode(app),
        foreground = foreground,
        updateMonitor = updateMonitor,
        isChatOpen = ::isChatOpen,
        bootStart = BootStart(app),
        appVersion = BuildConfig.VERSION_NAME,
    )

    /** the web frontend served over the LAN; on only while the setting is and the phone is set up */
    val dex = DexController(
        scope = appScope,
        prefs = prefs,
        profile = runtime.profile,
        backend = DexBackendAdapter(
            appScope, runtime, core, prefs, typing, peerPresence, transfers, peerQueues, attachments, dexCalls,
            vpn, updateMonitor, callEngine.diagnostics::value, BuildConfig.VERSION_NAME,
        ),
        bridge = dexCalls,
        assets = WebAssets(app),
        notices = notices,
    )

    val navigator = Navigator(appScope)
    val screenshots = ScreenshotPolicy(prefs, core, navigator, appScope)
    val presence = CallPresence(callEngine, navigator, appScope)
    val nebulaDraft = NebulaDraftStore(nebulaConfig)
    val root = RootController(app, appScope, runtime, navigator, callEngine, presence, appLock, gateway, notices)

    init {
        CoreNotificationConfig.smallIcon = R.drawable.ic_stat_notify
        CoreNotificationConfig.avatarBitmap = AvatarBitmaps::render
        CallsConfig.smallIcon = R.drawable.ic_stat_notify
        CallsConfig.avatarBitmap = AvatarBitmaps::render
        CallsConfig.launchIntent = { context ->
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CallsConfig.EXTRA_OPEN_CALL, true)
        }
    }

    /** The chat's own screen already gives in-place feedback; everywhere else still gets a pop-up.
     *  Reads [Navigator.topKey] (a StateFlow) rather than the backing snapshot list directly, since
     *  this runs off the main thread on the event-bus collector's dispatcher. */
    private fun isChatOpen(ip: String): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && (navigator.topKey.value as? Chat)?.peerIp == ip

    private fun callPrefs(): CallPrefs {
        val p = prefs.prefs.value
        return CallPrefs(
            ringForCalls = p.core.notifications.calls.ring,
            vibrateWhileRinging = p.core.notifications.calls.vibrate,
            missedNotification = p.core.notifications.calls.missedNotification,
            videoSpeakerDefault = p.app.isVideoSpeakerDefault,
            isVerboseLogging = p.core.nebulaLogLevel == NebulaLogLevel.DEBUG,
        )
    }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph is not provided") }
