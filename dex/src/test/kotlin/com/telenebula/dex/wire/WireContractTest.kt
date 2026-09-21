package com.telenebula.dex.wire

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser speaks this wire from its own module, which no JVM test can link against, so the
 * contract is checked against its source: a renamed field or serial name on either side is a
 * frame the other silently ignores.
 */
class WireContractTest {
    private val phone = File("src/main/kotlin/com/telenebula/dex/wire/DexWire.kt")
    private val web = File("../web/src/jsMain/kotlin/com/telenebula/web/wire/Wire.kt")

    private fun serialNames(file: File): List<String> =
        Regex("""@SerialName\("([^"]+)"\)""").findAll(file.readText()).map { it.groupValues[1] }.toSortedSet().toList()

    private fun fieldsByClass(file: File): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        for (match in Regex("""(?:data\s+)?class\s+(\w+)\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())) {
            val fields = Regex("""\bval\s+(\w+)\s*:""").findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
            if (fields.isNotEmpty()) out.putIfAbsent(match.groupValues[1], fields.sorted())
        }
        return out
    }

    @Test
    fun `both sides of the wire are present`() {
        assertTrue("the phone's wire is not where the contract test expects it", phone.isFile)
        assertTrue("the browser's wire is not where the contract test expects it", web.isFile)
    }

    @Test
    fun `every serial name on the phone exists in the browser and back`() {
        assertEquals(serialNames(phone), serialNames(web))
    }

    @Test
    fun `every shared class carries the same fields on both sides`() {
        val a = fieldsByClass(phone)
        val b = fieldsByClass(web)
        val shared = a.keys.intersect(b.keys)
        assertTrue("the two wires share no class; one of them was moved or renamed", shared.size > 20)
        for (name in shared) assertEquals("fields of $name differ across the wire", a[name], b[name])
        assertEquals("classes only on the phone", emptySet<String>(), a.keys - b.keys)
        assertEquals("classes only in the browser", emptySet<String>(), b.keys - a.keys)
    }
}
