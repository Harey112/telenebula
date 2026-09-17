package com.telenebula.calls.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.telenebula.calls.CallAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Relays notification and floating-window taps to whichever engine is collecting; nothing is held once it stops. */
object CallActionBus {
    private val flow = MutableSharedFlow<CallAction>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val actions: SharedFlow<CallAction> = flow.asSharedFlow()

    fun send(action: CallAction) {
        flow.tryEmit(action)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            CallNotifications.ACTION_ANSWER -> CallAction.ANSWER
            CallNotifications.ACTION_DECLINE -> CallAction.DECLINE
            CallNotifications.ACTION_HANGUP -> CallAction.HANGUP
            else -> return
        }
        if (action != CallAction.HANGUP) CallNotifications.hideIncoming(context)
        CallActionBus.send(action)
    }
}
