package com.telenebula.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.UpdateNotifier
import com.telenebula.app.platform.ScreenSecurity
import com.telenebula.app.runtime.RuntimeState
import com.telenebula.app.ui.root.RootShell
import com.telenebula.calls.CallsConfig
import com.telenebula.calls.system.OverlayPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The single activity: splash until the runtime knows whether an identity exists, then Compose. */
class MainActivity : FragmentActivity() {
    private val graph: AppGraph get() = appGraph
    /** app-scoped (survives Activity recreation) so a notification answered while backgrounded is never lost */
    val launchRequests: LaunchRequests get() = graph.launchRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { graph.runtime.state.value is RuntimeState.Booting }
        graph.gateway.attach(this)
        graph.runtime.onActivityAttached()
        lifecycleScope.launch {
            graph.screenshots.isBlocked.collect { ScreenSecurity.apply(this@MainActivity, it) }
        }
        launchRequests.offer(intent)
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                RootShell(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequests.offer(intent)
    }

    override fun onResume() {
        super.onResume()
        launchRequests.overlayPermitted.value = OverlayPermission.canDraw(this)
        if (graph.appLock.isLocked.value) graph.appLock.prompt(this)
    }

    override fun onDestroy() {
        graph.gateway.detach(this)
        super.onDestroy()
    }
}

/** What an Intent asked the UI to do: open a chat (deep link) or the call screen (notification). */
class LaunchRequests {
    val pendingChatIp = MutableStateFlow<String?>(null)
    val openCall = MutableStateFlow(false)
    val answerCall = MutableStateFlow(false)
    val openUpdates = MutableStateFlow(false)
    val overlayPermitted = MutableStateFlow(false)

    fun offer(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(CallsConfig.EXTRA_OPEN_CALL, false)) openCall.value = true
        if (intent.getBooleanExtra(CallsConfig.EXTRA_ANSWER_CALL, false)) answerCall.value = true
        if (intent.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATES, false)) openUpdates.value = true
        val data = intent.data
        if (data != null && data.scheme == "telenebula" && data.host == "chats") {
            // any app can send this link, so only a well-formed overlay address gets as far as the navigator
            data.pathSegments.firstOrNull()?.takeIf(ContactLabels::isOverlayIp)?.let { pendingChatIp.value = it }
        }
    }
}
