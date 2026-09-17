package com.telenebula.calls

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

/**
 * What only the application knows: its notification icon and how to open the call screen. A
 * library module has no resources of its own here, so the app sets these once at startup.
 */
object CallsConfig {
    @Volatile var smallIcon: Int = android.R.drawable.sym_call_incoming

    /** Intent that brings the app to its call screen (extra [EXTRA_OPEN_CALL] set). */
    @Volatile var launchIntent: (Context) -> Intent = { context ->
        context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    }

    /** Renders the peer's avatar for incoming/ongoing/missed call notifications; null skips it. */
    @Volatile var avatarBitmap: (String) -> Bitmap? = { null }

    const val EXTRA_OPEN_CALL = "com.telenebula.calls.OPEN_CALL"

    /** Set on the launch intent by the incoming notification's Answer action, which opens the app and answers in one step. */
    const val EXTRA_ANSWER_CALL = "com.telenebula.calls.ANSWER_CALL"
}

/** What a notification button or the floating window asked for. */
enum class CallAction { ANSWER, DECLINE, HANGUP, OPEN }
