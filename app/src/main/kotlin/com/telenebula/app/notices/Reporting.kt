package com.telenebula.app.notices

import com.telenebula.app.platform.userMessage
import kotlinx.coroutines.CancellationException

/** Runs [block]; any failure but cancellation becomes one error notice, so a cancelled scope never pops an error on the way out. */
suspend fun NoticeCenter.reporting(what: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        addError("$what: ${e.userMessage()}")
    }
}
