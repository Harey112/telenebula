package com.telenebula.core

/**
 * The running core, for the two callers that have no object graph to reach it through: the
 * notification receivers, which the system starts on their own. Everything else is handed a
 * [CoreClient].
 */
internal object CoreRegistry {
    @Volatile
    private var core: MessagingCore? = null

    fun register(core: MessagingCore) {
        this.core = core
    }

    /** The core once its store is open, or null when the process has not got that far. */
    fun current(): MessagingCore? = core
}
