package com.telenebula.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.telenebula.app.appGraph

/**
 * Boot or an update. The runtime is already booting from Application.onCreate; all this does is
 * start the foreground service, synchronously, so the process outlives the broadcast while it
 * finishes. Nothing here waits: a receiver that holds its broadcast open is given sixty seconds,
 * and the runtime's start may wait on the user for longer than that.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.appGraph.runtime.onSystemStart()
    }
}
