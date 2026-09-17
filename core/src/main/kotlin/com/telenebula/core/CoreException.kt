package com.telenebula.core

/**
 * Every failure the core reports, as one exception type carrying what went wrong. The client
 * turns it into a notice; nothing above this layer switches on the kind except the storage
 * paths, which need to tell "the device is full" from every other way a write can fail.
 */
class CoreException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { DB, SERDE, PROTOCOL, UNREACHABLE, NOT_RUNNING, OUT_OF_SPACE, INTERNAL }

    companion object {
        fun db(message: String, cause: Throwable? = null) = CoreException(Kind.DB, "database error: $message", cause)

        fun protocol(message: String) = CoreException(Kind.PROTOCOL, "protocol error: $message")

        fun unreachable(message: String) = CoreException(Kind.UNREACHABLE, "peer unreachable: $message")

        fun notRunning() = CoreException(Kind.NOT_RUNNING, "core is not running")

        fun outOfSpace(message: String) = CoreException(Kind.OUT_OF_SPACE, "not enough storage: $message")

        fun internal(message: String, cause: Throwable? = null) = CoreException(Kind.INTERNAL, message, cause)
    }
}
