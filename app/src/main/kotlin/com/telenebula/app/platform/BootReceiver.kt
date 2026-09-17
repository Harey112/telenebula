package com.telenebula.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.telenebula.app.appGraph

/** Android started the process for this broadcast; the runtime's boot is already bringing everything up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.appGraph.runtime.onSystemStart { pending.finish() }
    }
}
