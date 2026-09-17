package com.telenebula.vpn

/** A nebula binding or VpnService call failed; the message is safe to show. */
open class NebulaVpnException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NebulaNotRunningException : NebulaVpnException("Nebula is not running")
