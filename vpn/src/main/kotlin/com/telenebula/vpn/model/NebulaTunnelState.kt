package com.telenebula.vpn.model

import kotlinx.serialization.Serializable

/** Process-wide tunnel status; startedAt is epoch ms while running, 0 while down. */
@Serializable
data class NebulaTunnelState(
    val running: Boolean = false,
    val error: String? = null,
    val startedAt: Long = 0,
)
