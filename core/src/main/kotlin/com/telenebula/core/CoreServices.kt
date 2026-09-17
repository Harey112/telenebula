package com.telenebula.core

import android.content.Context
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.notify.MessageNotifications
import com.telenebula.core.notify.NotificationPrefsStore
import com.telenebula.core.service.TnCoreService

/** The Context-bound pieces behind the client, as a seam so the client is unit-testable off-device. */
interface CoreServices {
    fun clearChatNotification(ip: String)
    fun setNotificationPrefs(prefs: NotificationPrefs)
    fun startBackgroundService()
    fun stopBackgroundService()
    fun setTunnelState(running: Boolean)
}

class AndroidCoreServices(context: Context) : CoreServices {
    private val context = context.applicationContext

    override fun clearChatNotification(ip: String) = MessageNotifications.clear(context, ip)
    override fun setNotificationPrefs(prefs: NotificationPrefs) = NotificationPrefsStore.set(context, prefs)
    override fun startBackgroundService() = TnCoreService.start(context)
    override fun stopBackgroundService() = TnCoreService.stop(context)
    override fun setTunnelState(running: Boolean) = TnCoreService.setTunnelState(context, running)
}
