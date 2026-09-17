package com.telenebula.app.nebula

import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.nebula.NebulaConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NebulaDraftStoreTest {
    private fun store() = NebulaDraftStore(NebulaConfigRepository()).also { it.hydrateFrom(NebulaAdvancedConfig(), 4433) }

    @Test
    fun `hydrating fills the editor tables and a commit of the defaults has no errors`() {
        val s = store()
        assertTrue(s.state.value.sections.isNotEmpty())
        val result = s.commit()
        assertTrue(result.errors.isEmpty())
        assertFalse("a clean commit does not open the fold", s.state.value.isOpen)
    }

    @Test
    fun `an invalid value is reported and opens the fold`() {
        val s = store()
        val path = s.state.value.sections.flatMap { it.fields }.first { it.kind?.name == "NUMBER" }.path
        s.setValue(path, "not a number")
        val result = s.commit()
        assertTrue(result.errors.containsKey(path))
        assertTrue(s.state.value.isOpen)
        assertEquals(result.errors, s.state.value.errors)
    }

    @Test
    fun `rules and routes are added, edited and removed by id`() {
        val s = store()
        s.addRule(RuleDirection.INBOUND)
        s.addRule(RuleDirection.OUTBOUND)
        val inbound = s.state.value.draft.inbound.single()
        s.updateRule(RuleDirection.INBOUND, inbound.copy(port = "443"))
        assertEquals("443", s.state.value.draft.inbound.single().port)
        assertEquals(1, s.state.value.draft.outbound.size)
        s.removeRule(RuleDirection.INBOUND, inbound.id)
        assertTrue(s.state.value.draft.inbound.isEmpty())

        s.addUnsafeRoute()
        val route = s.state.value.draft.unsafeRoutes.single()
        s.updateUnsafeRoute(route.copy(route = "10.0.0.0/8"))
        assertEquals("10.0.0.0/8", s.state.value.draft.unsafeRoutes.single().route)
        s.removeUnsafeRoute(route.id)
        assertTrue(s.state.value.draft.unsafeRoutes.isEmpty())
    }

    @Test
    fun `sections toggle independently`() {
        val s = store()
        s.toggleSection("a")
        s.toggleSection("b")
        s.toggleSection("a")
        assertEquals(setOf("b"), s.state.value.openSections)
    }
}
