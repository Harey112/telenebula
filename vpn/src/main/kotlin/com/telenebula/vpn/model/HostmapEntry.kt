package com.telenebula.vpn.model

/**
 * One nebula hostmap entry (from mobile_nebula's ListHostmap JSON), reduced to
 * what the app shows. Fields the binding version does not provide are null.
 */
data class HostmapEntry(
    val vpnIp: String,
    /** the UDP underlay endpoint nebula is currently using */
    val currentRemote: String?,
    val remoteAddrs: List<String>,
    /** peer certificate details when nebula exposes them */
    val certName: String?,
    val certFingerprint: String?,
    /** nebula relays the traffic instead of a direct hole-punched path */
    val isRelayed: Boolean,
    val isLighthouse: Boolean,
)
