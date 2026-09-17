package com.telenebula.calls.system

import android.content.Context
import com.telenebula.calls.CallSystem

/** The engine's notification, foreground-service and floating-window seam on Android. */
class AndroidCallSystem(context: Context) : CallSystem {
    private val app = context.applicationContext

    override fun startSession(peerName: String, video: Boolean, connectedAt: Long) = CallSessionService.start(app, peerName, video, connectedAt)
    override fun stopSession() = CallSessionService.stop(app)
    override fun showIncoming(peerName: String, video: Boolean, vibrate: Boolean) = CallNotifications.showIncoming(app, peerName, video, vibrate)
    override fun hideIncoming() = CallNotifications.hideIncoming(app)
    override fun hideFloating() = FloatingCallWindow.hide()
    override fun showMissed(peerName: String, video: Boolean) = CallNotifications.showMissed(app, peerName, video)
}
