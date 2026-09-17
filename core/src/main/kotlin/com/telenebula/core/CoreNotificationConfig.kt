package com.telenebula.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

/**
 * Library modules ship no resources, so the app hands over the small icon and
 * the launch intent once at startup; both notification posters read them here.
 */
object CoreNotificationConfig {
    /** 0 falls back to the application icon */
    @Volatile var smallIcon: Int = 0

    @Volatile var launchIntent: (Context) -> Intent = { context ->
        context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    }

    /** Renders a peer's avatar (name/nickname keyed) for message and reaction notifications; null skips it. */
    @Volatile var avatarBitmap: (String) -> Bitmap? = { null }

    fun smallIcon(context: Context): Int =
        if (smallIcon != 0) smallIcon else context.applicationInfo.icon
}
