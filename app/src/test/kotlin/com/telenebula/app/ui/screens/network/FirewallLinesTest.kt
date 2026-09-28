package com.telenebula.app.ui.screens.network

import com.telenebula.core.model.FirewallMatch
import com.telenebula.core.model.FirewallProto
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaFirewall
import com.telenebula.core.nebula.NebulaConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallLinesTest {
    @Test
    fun `the listing is the rules the tunnel runs with, the app's own first and then the user's`() {
        val user = FirewallRule(id = "r1", proto = FirewallProto.TCP, port = "22", match = FirewallMatch.GROUP, value = "admins")
        val lines = firewallLines(NebulaConfigRepository().requiredRules(4433), NebulaFirewall(inbound = listOf(user)))
        assertTrue(lines.first().startsWith("inbound icmp any"))
        assertTrue(lines.any { it.startsWith("inbound tcp 4433") })
        assertEquals("inbound tcp 22  from group admins", lines.last())
    }
}
