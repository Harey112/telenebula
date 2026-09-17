package com.telenebula.calls.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** SYSTEM_ALERT_WINDOW: granted in system settings, so the app re-reads it on every resume. */
object OverlayPermission {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}
