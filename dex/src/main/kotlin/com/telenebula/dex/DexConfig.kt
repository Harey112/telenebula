package com.telenebula.dex

/** A stored password: the algorithm named so a later build can verify what an earlier one wrote. */
class PasswordHash(
    val algorithm: String = ALGORITHM,
    val iterations: Int,
    val saltB64: String,
    val hashB64: String,
) {
    companion object {
        const val ALGORITHM = "pbkdf2-sha256"
    }
}

class DexConfig(
    val port: Int,
    val username: String,
    val password: PasswordHash,
    val maxClients: Int,
)

enum class DexFailure { CERTIFICATE, BIND, IO, PROTOCOL, AUTH, LIMIT }

class DexException(val kind: DexFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface DexServerState {
    data object Off : DexServerState
    data object Starting : DexServerState
    data class Running(val port: Int) : DexServerState
    data class Failed(val message: String) : DexServerState
}

/** A browser with a live socket. */
data class DexClient(val id: String, val remoteAddress: String, val userAgent: String, val connectedAt: Long)

class TurnCredential(val username: String, val password: String)

/** The relay a browser's call media rides over; the server only hands its address and a credential to the browser. */
interface TurnAccess {
    val port: Int
    fun issue(): TurnCredential
}
