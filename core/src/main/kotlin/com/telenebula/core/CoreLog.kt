package com.telenebula.core

import android.util.Log

/**
 * Where the engine's own diagnostics go. It exists because `android.util.Log` is a stub outside a
 * device and throws when called, which would take a coroutine down with it — the engine must be
 * runnable, and testable, without a device. A log line is never the only report of a failure:
 * anything the user should know about travels as an exception or a notice.
 */
internal object CoreLog {
    fun info(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
    }

    fun warn(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
    }
}
