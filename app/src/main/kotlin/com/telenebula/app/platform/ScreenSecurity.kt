package com.telenebula.app.platform

import android.app.Activity
import android.view.WindowManager

/** FLAG_SECURE blocks screenshots and screen recording of the whole window. */
object ScreenSecurity {
    fun apply(activity: Activity, isBlocked: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (isBlocked) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }
}
