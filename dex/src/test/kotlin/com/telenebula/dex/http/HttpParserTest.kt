package com.telenebula.dex.http

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpParserTest {
    private fun parse(head: String): HttpRequest = HttpParser.read(ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1))) ?: fail("no request")

    private fun status(head: String): Int = try {
        parse(head)
        fail("parsed")
    } catch (e: HttpError) {
        e.status
    }

    @Test
    fun `a plain request parses with lower-cased headers, query and keep-alive`() {
        val r = parse("GET /a/b%20c?peer=10.0.0.2&x=1%2B1&flag HTTP/1.1\r\nHost: phone:8420\r\nCONTENT-length: 0\r\nCookie: dex=abc; other=1\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/a/b c", r.path)
        assertEquals(mapOf("peer" to "10.0.0.2", "x" to "1+1", "flag" to ""), r.query)
        assertEquals("phone:8420", r.header("HOST"))
        assertEquals(0L, r.contentLength)
        assertTrue(r.isKeepAlive)
        assertEquals("abc", r.cookie("dex"))
        assertNull(r.cookie("missing"))
    }

    @Test
    fun `http 1_0 closes unless asked to stay, http 1_1 stays unless asked to close`() {
        assertFalse(parse("GET / HTTP/1.0\r\n\r\n").isKeepAlive)
        assertTrue(parse("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").isKeepAlive)
        assertFalse(parse("GET / HTTP/1.1\r\nConnection: close\r\n\r\n").isKeepAlive)
    }

    @Test
    fun `repeated headers join and the body length is read`() {
        val r = parse("POST /a HTTP/1.1\r\nX-A: 1\r\nX-A: 2\r\nContent-Length: 12\r\n\r\n")
        assertEquals("1, 2", r.header("x-a"))
        assertEquals(12L, r.contentLength)
    }

    @Test
    fun `malformed heads answer with the right status`() {
        assertEquals(400, status("GET /\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1 extra\r\n\r\n"))
        assertEquals(405, status("BREW / HTTP/1.1\r\n\r\n"))
        assertEquals(505, status("GET / HTTP/2.0\r\n\r\n"))
        assertEquals(400, status("GET nopath HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /%zz HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /%2 HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nNoColon\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nBad Name: x\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nContent-Length: abc\r\n\r\n"))
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: x"))
        assertEquals(400, status("GET /../etc HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /%2e%2e/x HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `dot segments collapse so no path can climb`() {
        assertEquals("/b", parse("GET /a/../b HTTP/1.1\r\n\r\n").path)
        assertEquals("/a/b", parse("GET //a/./b/ HTTP/1.1\r\n\r\n").path)
        assertEquals("/", parse("GET / HTTP/1.1\r\n\r\n").path)
    }

    @Test
    fun `an oversized head is refused before it is buffered whole`() {
        val head = "GET / HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n"
        assertEquals(431, status(head))
    }

    @Test
    fun `a clean end of stream is no request`() {
        assertNull(HttpParser.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `percent decoding keeps utf8 and rejects bad escapes`() {
        assertEquals("héllo wörld", HttpParser.percentDecode("h%C3%A9llo%20w%C3%B6rld"))
        assertNull(HttpParser.percentDecode("%C3"))
        assertNull(HttpParser.percentDecode("%G1"))
        assertNull(HttpParser.percentDecode("%FF%FE"))
    }

    @Test
    fun `range headers parse into one satisfiable range or nothing`() {
        assertTrue(Ranges.parse(null, 100) is RangeResult.None)
        val a = Ranges.parse("bytes=0-9", 100) as RangeResult.Satisfiable
        assertEquals(0L, a.range.start)
        assertEquals(9L, a.range.endInclusive)
        assertEquals(10L, a.range.length)
        val open = Ranges.parse("bytes=90-", 100) as RangeResult.Satisfiable
        assertEquals(90L, open.range.start)
        assertEquals(99L, open.range.endInclusive)
        val suffix = Ranges.parse("bytes=-10", 100) as RangeResult.Satisfiable
        assertEquals(90L, suffix.range.start)
        val clamped = Ranges.parse("bytes=50-500", 100) as RangeResult.Satisfiable
        assertEquals(99L, clamped.range.endInclusive)
        assertTrue(Ranges.parse("bytes=100-", 100) is RangeResult.Unsatisfiable)
        assertTrue(Ranges.parse("bytes=5-4", 100) is RangeResult.Unsatisfiable)
        assertTrue(Ranges.parse("bytes=0-1,3-4", 100) is RangeResult.Unsatisfiable)
        assertTrue(Ranges.parse("items=0-1", 100) is RangeResult.Unsatisfiable)
        assertTrue(Ranges.parse("bytes=abc", 100) is RangeResult.Unsatisfiable)
        assertTrue(Ranges.parse("bytes=-0", 100) is RangeResult.Unsatisfiable)
        assertTrue(Ranges.parse("bytes=0-", 0) is RangeResult.Unsatisfiable)
    }

    @Test
    fun `a body reader stops at the declared length and reports a short body`() {
        val input = ByteArrayInputStream("hello world".toByteArray())
        val reader = BodyReader(input, 5)
        assertEquals("hello", String(reader.readAll(10)))
        assertTrue(reader.isDrained)
        val short = BodyReader(ByteArrayInputStream("hi".toByteArray()), 5)
        try {
            short.readAll(10)
            fail("short body accepted")
        } catch (e: java.io.EOFException) {
            // expected
        }
        try {
            BodyReader(ByteArrayInputStream(ByteArray(0)), 100).readAll(10)
            fail("oversized body accepted")
        } catch (e: HttpError) {
            assertEquals(413, e.status)
        }
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)
