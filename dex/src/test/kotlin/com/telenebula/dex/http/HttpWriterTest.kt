package com.telenebula.dex.http

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpWriterTest {
    private fun write(response: HttpResponse, isHead: Boolean = false, isKeepAlive: Boolean = true): Pair<String, ByteArray> {
        val out = ByteArrayOutputStream()
        HttpWriter.write(out, response, isHead, isKeepAlive)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        return text.substring(0, split) to bytes.copyOfRange(split + 4, bytes.size)
    }

    @Test
    fun `every response carries an exact length and the security headers`() {
        val (head, body) = write(HttpResponse.text(200, "héllo"))
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("Content-Length: 6\r\n"))
        assertTrue(head.contains("X-Content-Type-Options: nosniff"))
        assertTrue(head.contains("Content-Security-Policy: default-src 'self'"))
        assertTrue(head.contains("Connection: keep-alive"))
        assertTrue(head.contains("Cache-Control: no-store"))
        assertEquals("héllo", String(body, Charsets.UTF_8))
    }

    @Test
    fun `head requests carry the length but no body, and close is honoured`() {
        val (head, body) = write(HttpResponse.text(200, "abc"), isHead = true, isKeepAlive = false)
        assertTrue(head.contains("Content-Length: 3"))
        assertTrue(head.contains("Connection: close"))
        assertEquals(0, body.size)
    }

    @Test
    fun `a ranged stream copies exactly the requested window of a file`() {
        val file = File.createTempFile("dex", ".bin")
        try {
            file.writeBytes(ByteArray(1_000) { it.toByte() })
            val range = (Ranges.parse("bytes=100-199", file.length()) as RangeResult.Satisfiable).range
            val body = HttpBody.Stream(range.length) { FileInputStream(file).also { it.skip(range.start) } }
            val response = HttpResponse(206, body, "application/octet-stream", listOf("Content-Range" to "bytes ${range.start}-${range.endInclusive}/${file.length()}"))
            val (head, bytes) = write(response)
            assertTrue(head.startsWith("HTTP/1.1 206 Partial Content"))
            assertTrue(head.contains("Content-Range: bytes 100-199/1000"))
            assertTrue(head.contains("Content-Length: 100"))
            assertEquals(100, bytes.size)
            assertEquals(100.toByte(), bytes[0])
            assertEquals(199.toByte(), bytes[99])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a disposition header survives any file name`() {
        assertEquals("inline; filename*=UTF-8''r%C3%A9sum%C3%A9%20%22final%22.pdf", HttpResponse.contentDisposition("résumé \"final\".pdf"))
    }
}
