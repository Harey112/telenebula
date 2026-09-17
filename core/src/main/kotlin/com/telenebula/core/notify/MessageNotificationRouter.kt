package com.telenebula.core.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Turns message/reaction events into system notifications for every peer except the one whose
 * chat is currently open and visible — the open chat gives its own in-place feedback instead, but
 * anywhere else in the app (another chat, another tab) still gets a pop-up when enabled.
 */
class MessageNotificationRouter(
    context: Context,
    private val bus: CoreEventBus,
    private val scope: CoroutineScope,
    private val isChatOpen: (ip: String) -> Boolean,
) {
    private val context = context.applicationContext

    fun start(): Job = scope.launch {
        bus.events.collect { event ->
            when (event) {
                is CoreEvent.MessageReceived -> if (event.ip.isNotEmpty() && shouldPost(event.ip)) {
                    MessageNotifications.post(
                        context,
                        event.ip,
                        event.name.ifEmpty { event.ip },
                        event.preview.ifEmpty { "New message" },
                        event.isMuted,
                        event.notifications,
                    )
                }
                is CoreEvent.ReactionReceived -> if (event.ip.isNotEmpty() && shouldPost(event.ip)) {
                    MessageNotifications.postReaction(
                        context,
                        event.ip,
                        event.name.ifEmpty { event.ip },
                        event.emoji,
                        event.preview,
                        event.isMuted,
                        event.notifications,
                    )
                }
                else -> Unit
            }
        }
    }

    private fun shouldPost(ip: String): Boolean = hasPermission() && !isChatOpen(ip)

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
