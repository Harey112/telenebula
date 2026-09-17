package com.telenebula.core.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.telenebula.core.CoreRegistry
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.service.TnCoreService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Notification buttons: inline message reply, and the tunnel toggle. */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MessageNotifications.ACTION_REPLY -> {
                val ip = intent.getStringExtra(MessageNotifications.EXTRA_IP) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(MessageNotifications.KEY_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) {
                    MessageNotifications.clear(context, ip)
                    return
                }
                // SQLite through the core must leave the main thread; the receiver stays alive until finish()
                val pending = goAsync()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch {
                    try {
                        withTimeout(REPLY_TIMEOUT_MS) {
                            // the core runs in this process (kept alive by the foreground service)
                            val core = CoreRegistry.current() ?: return@withTimeout
                            core.sendText(ip, text, null)
                            // reading the chat here also reports the messages as seen
                            core.markChatRead(ip)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "inline reply failed: ${e.message}")
                        CoreEventBus.emit(CoreEvent.EngineFault("Couldn't send the reply", e.message ?: e.javaClass.simpleName))
                    } finally {
                        MessageNotifications.clear(context, ip)
                        pending.finish()
                        scope.cancel()
                    }
                }
            }
            TnCoreService.ACTION_TOGGLE_TUNNEL -> CoreEventBus.emit(CoreEvent.TunnelToggle)
        }
    }

    private companion object {
        const val TAG = "MessageActionReceiver"

        /** goAsync() gives a receiver about ten seconds; giving up before that keeps the process off the ANR list. */
        const val REPLY_TIMEOUT_MS = 8_000L
    }
}
