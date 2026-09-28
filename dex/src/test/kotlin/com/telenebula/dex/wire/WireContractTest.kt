package com.telenebula.dex.wire

import java.io.File
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireContractTest {
    private val phone = File("src/main/kotlin/com/telenebula/dex/wire/DexWire.kt")
    private val solidModels = File("../web/src/wire/models.ts")
    private val solidFrames = File("../web/src/wire/frames.ts")
    private val serverFixture = File("../web/test/fixtures/server-chat.json")
    private val clientFixture = File("../web/test/fixtures/client-send-text.json")

    @Test
    fun `phone and Solid wire sources are present`() {
        assertTrue("the phone's wire is not where the contract test expects it", phone.isFile)
        assertTrue("the Solid models are missing", solidModels.isFile)
        assertTrue("the Solid frames are missing", solidFrames.isFile)
    }

    @Test
    fun `Solid models mirror the phone's field names and optionality`() {
        val phoneModels = modelFields(phone.readText())
        val browserModels = typescriptFields(solidModels.readText())
        assertEquals(phoneModels.keys, browserModels.keys)
        for (name in phoneModels.keys) assertEquals("fields of $name differ", phoneModels[name], browserModels[name])
    }

    @Test
    fun `Solid model field types mirror the phone`() {
        val kotlin = modelTypes(phone.readText())
        val typescript = typescriptTypes(solidModels.readText())
        assertEquals(kotlin.keys, typescript.keys)
        for (name in kotlin.keys) assertEquals("field types of $name differ", kotlin[name], typescript[name])
    }

    @Test
    fun `Solid frame discriminators mirror the phone`() {
        val source = phone.readText()
        val typescript = solidFrames.readText()
        for (kind in listOf("ClientFrame", "ServerFrame")) {
            val kotlinBlock = source.substringAfter("sealed interface $kind {").substringBefore("\n}")
            val typescriptBlock = typescript.substringAfter("export type $kind =")
                .let { if (kind == "ClientFrame") it.substringBefore("export type ServerFrame") else it }
            val expected = Regex("""@SerialName\("([^"]+)"\)\s+data\s+(?:class|object)""")
                .findAll(kotlinBlock).map { it.groupValues[1] }.toSet()
            val actual = Regex("""\bt:\s*"([^"]+)"""")
                .findAll(typescriptBlock).map { it.groupValues[1] }.toSet()
            assertTrue("no $kind discriminators found", expected.isNotEmpty())
            assertEquals("$kind discriminators differ", expected, actual)
        }
    }

    @Test
    fun `Solid enum values mirror the phone's serial names`() {
        val source = solidModels.readText()
        for (match in Regex("""enum class (Dex\w+)\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL).findAll(phone.readText())) {
            val name = match.groupValues[1]
            val expected = Regex("""@SerialName\("([^"]+)"\)""")
                .findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
            val declaration = Regex("""export type $name\s*=\s*([^;]+);""")
                .find(source)?.groupValues?.get(1) ?: error("$name is missing from Solid models")
            val actual = Regex(""""([^"]+)"""").findAll(declaration).map { it.groupValues[1] }.toSet()
            assertEquals("$name serial names differ", expected, actual)
        }
    }

    @Test
    fun `Kotlin server frame matches the browser fixture`() {
        val frame = ServerFrame.Chat(
            DexChatView(
                peer = "10.0.0.2",
                contact = DexContact(ip = "10.0.0.2", label = "Alice", name = "Alice"),
                messages = listOf(
                    DexMessage(
                        id = "m1", peer = "10.0.0.2", dir = DexDirection.IN, body = "hello",
                        ts = 42, status = DexMessageStatus.DELIVERED, kind = DexMessageKind.TEXT,
                    ),
                ),
                hasMore = false,
            ),
        )
        val actual = DexJson.parseToJsonElement(DexJson.encodeToString(ServerFrame.serializer(), frame))
        val expected = DexJson.decodeFromString<JsonElement>(serverFixture.readText())
        assertEquals(expected, actual)
    }

    @Test
    fun `Kotlin decodes the browser client fixture`() {
        val frame = DexJson.decodeFromString(ClientFrame.serializer(), clientFixture.readText())
        assertEquals(ClientFrame.SendText("10.0.0.2", "hello", covered = true, requestId = "r1"), frame)
    }

    private fun modelFields(source: String): Map<String, Map<String, Boolean>> {
        val clean = source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        val result = sortedMapOf<String, Map<String, Boolean>>()
        for (match in Regex("""data\s+class\s+(Dex\w+)\s*\(""").findAll(clean)) {
            val name = match.groupValues[1]
            val constructor = balanced(clean, match.range.last, '(', ')')
            result[name] = splitFields(constructor).mapNotNull { field ->
                val parsed = Regex("""val\s+(\w+)\s*:\s*([^=]+)(?:=.*)?""").matchEntire(field.trim()) ?: return@mapNotNull null
                parsed.groupValues[1] to ('=' in field)
            }.toMap()
        }
        return result
    }

    private fun typescriptFields(source: String): Map<String, Map<String, Boolean>> =
        Regex("""export interface (Dex\w+)\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).associate { match ->
                match.groupValues[1] to Regex("""\b(\w+)(\?)?\s*:\s*[^;]+;?""")
                    .findAll(match.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].isNotEmpty() }
            }.toSortedMap()

    private fun modelTypes(source: String): Map<String, Map<String, String>> {
        val clean = source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""data\s+class\s+(Dex\w+)\s*\(""").findAll(clean).associate { match ->
            val constructor = balanced(clean, match.range.last, '(', ')')
            match.groupValues[1] to splitFields(constructor).mapNotNull { field ->
                val declaration = field.substringBefore('=').trim()
                val parsed = Regex("""val\s+(\w+)\s*:\s*(.+)""").matchEntire(declaration) ?: return@mapNotNull null
                parsed.groupValues[1] to normalizeType(parsed.groupValues[2])
            }.toMap()
        }.toSortedMap()
    }

    private fun typescriptTypes(source: String): Map<String, Map<String, String>> =
        Regex("""export interface (Dex\w+)\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).associate { match ->
                match.groupValues[1] to Regex("""\b(\w+)\??\s*:\s*([^;]+);""")
                    .findAll(match.groupValues[2] + ";").associate { it.groupValues[1] to it.groupValues[2].replace(Regex("""\s+"""), "") }
            }.toSortedMap()

    private fun normalizeType(type: String): String = type.trim().removeSuffix("?")
        .replace(Regex("""Map<String,\s*(\w+)>"""), "Record<string,$1>")
        .replace(Regex("""List<(\w+)>"""), "$1[]")
        .replace("String", "string")
        .replace("Long", "number")
        .replace("Int", "number")
        .replace("Boolean", "boolean")
        .replace(Regex("""\s+"""), "")

    private fun balanced(source: String, open: Int, start: Char, end: Char): String {
        var depth = 0
        var inString = false
        for (i in open until source.length) {
            val c = source[i]
            if (c == '"' && (i == 0 || source[i - 1] != '\\')) inString = !inString
            if (inString) continue
            if (c == start) depth += 1
            if (c == end && --depth == 0) return source.substring(open + 1, i)
        }
        error("unclosed constructor")
    }

    private fun splitFields(source: String): List<String> {
        val fields = ArrayList<String>()
        var start = 0
        var parens = 0
        var angles = 0
        var inString = false
        for (i in source.indices) {
            val c = source[i]
            if (c == '"' && (i == 0 || source[i - 1] != '\\')) inString = !inString
            if (inString) continue
            when (c) {
                '(' -> parens += 1
                ')' -> parens -= 1
                '<' -> angles += 1
                '>' -> angles -= 1
                ',' -> if (parens == 0 && angles == 0) {
                    fields.add(source.substring(start, i))
                    start = i + 1
                }
            }
        }
        fields.add(source.substring(start))
        return fields
    }
}
