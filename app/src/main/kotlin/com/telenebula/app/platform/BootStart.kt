package com.telenebula.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** The boot receiver is switched on or off as a component, so a disabled setting costs nothing at boot. */
class BootStart(context: Context) {
    private val app = context.applicationContext
    private val component = ComponentName(app, BootReceiver::class.java)

    fun setEnabled(isEnabled: Boolean) {
        val wanted = if (isEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (app.packageManager.getComponentEnabledSetting(component) != wanted) {
            app.packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }
    }
}
