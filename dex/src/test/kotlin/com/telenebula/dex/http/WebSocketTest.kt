package com.telenebula.dex.http

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketTest {
    private val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)

    private fun read(bytes: ByteArray): WsRawFrame = WsCodec.readFrame(ByteArrayInputStream(bytes))

    private fun closeCode(bytes: ByteArray, assembler: WsAssembler = WsAssembler()): Int = try {
        assembler.feed(read(bytes))
        fail("accepted")
    } catch (e: WsCloseException) {
        e.code
    }

    @Test
    fun `the accept key matches the rfc example`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `a masked text frame round trips, including the rfc sample`() {
        val rfc = byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xFA.toByte(), 0x21, 0x3D, 0x7F, 0x9F.toByte(), 0x4D, 0x51, 0x58)
        val frame = read(rfc)
        assertEquals(WsOpcode.TEXT, frame.opcode)
        assertTrue(frame.isFin)
        assertEquals("Hello", String(frame.payload))
        assertArrayEquals(rfc, WsCodec.encodeMasked(WsOpcode.TEXT, "Hello".toByteArray(), mask))
        val text = WsAssembler().feed(frame) as WsMessage.Text
        assertEquals("Hello", text.text)
    }

    @Test
    fun `server frames are unmasked with two and eight byte lengths`() {
        val medium = WsCodec.encode(WsOpcode.TEXT, ByteArray(300))
        assertEquals(0x81.toByte(), medium[0])
        assertEquals(126.toByte(), medium[1])
        assertEquals(300, ((medium[2].toInt() and 0xFF) shl 8) or (medium[3].toInt() and 0xFF))
        assertEquals(304, medium.size)
        val large = WsCodec.encode(WsOpcode.TEXT, ByteArray(70_000))
        assertEquals(127.toByte(), large[1])
        assertEquals(70_010, large.size)
        val close = WsCodec.encodeClose(1002, "why")
        assertEquals(0x88.toByte(), close[0])
        assertEquals(5.toByte(), close[1])
        assertEquals(1002, ((close[2].toInt() and 0xFF) shl 8) or (close[3].toInt() and 0xFF))
    }

    @Test
    fun `fragments join in order and a stray continuation is a protocol error`() {
        val assembler = WsAssembler()
        assertNull(assembler.feed(read(WsCodec.encodeMasked(WsOpcode.TEXT, "Hel".toByteArray(), mask, isFin = false))))
        assertNull(assembler.feed(read(WsCodec.encodeMasked(WsOpcode.CONTINUATION, "lo ".toByteArray(), mask, isFin = false))))
        val done = assembler.feed(read(WsCodec.encodeMasked(WsOpcode.CONTINUATION, "there".toByteArray(), mask))) as WsMessage.Text
        assertEquals("Hello there", done.text)
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(WsCodec.encodeMasked(WsOpcode.CONTINUATION, "x".toByteArray(), mask)))
        val open = WsAssembler()
        open.feed(read(WsCodec.encodeMasked(WsOpcode.TEXT, "a".toByteArray(), mask, isFin = false)))
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(WsCodec.encodeMasked(WsOpcode.TEXT, "b".toByteArray(), mask), open))
    }

    @Test
    fun `control frames pass through a fragmented message`() {
        val assembler = WsAssembler()
        assembler.feed(read(WsCodec.encodeMasked(WsOpcode.TEXT, "a".toByteArray(), mask, isFin = false)))
        assertTrue(assembler.feed(read(WsCodec.encodeMasked(WsOpcode.PING, "p".toByteArray(), mask))) is WsMessage.Ping)
        val done = assembler.feed(read(WsCodec.encodeMasked(WsOpcode.CONTINUATION, "b".toByteArray(), mask))) as WsMessage.Text
        assertEquals("ab", done.text)
    }

    @Test
    fun `forbidden frames close with the right code`() {
        val unmasked = WsCodec.encode(WsOpcode.TEXT, "x".toByteArray())
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(unmasked))
        assertEquals(WsClose.UNSUPPORTED_DATA, closeCode(WsCodec.encodeMasked(WsOpcode.BINARY, byteArrayOf(1), mask)))
        val rsv = WsCodec.encodeMasked(WsOpcode.TEXT, "x".toByteArray(), mask).also { it[0] = (it[0].toInt() or 0x40).toByte() }
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(rsv))
        val longControl = WsCodec.encodeMasked(WsOpcode.TEXT, ByteArray(126), mask).also { it[0] = 0x89.toByte() }
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(longControl))
        val fragmentedPing = WsCodec.encodeMasked(WsOpcode.PING, "x".toByteArray(), mask, isFin = false)
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(fragmentedPing))
        val badUtf8 = WsCodec.encodeMasked(WsOpcode.TEXT, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), mask)
        assertEquals(WsClose.BAD_PAYLOAD, closeCode(badUtf8))
        val oneByteClose = WsCodec.encodeMasked(WsOpcode.CLOSE, byteArrayOf(0x03), mask)
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(oneByteClose))
        val badCode = WsCodec.encodeMasked(WsOpcode.CLOSE, byteArrayOf(0x03, 0xEC.toByte()), mask)
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(badCode))
        val unknownOpcode = WsCodec.encodeMasked(WsOpcode.TEXT, "x".toByteArray(), mask).also { it[0] = 0x83.toByte() }
        assertEquals(WsClose.PROTOCOL_ERROR, closeCode(unknownOpcode))
    }

    @Test
    fun `oversized frames and messages are too big`() {
        val huge = WsCodec.encodeMasked(WsOpcode.TEXT, ByteArray(65 * 1024), mask)
        assertEquals(WsClose.TOO_BIG, closeCode(huge))
        val assembler = WsAssembler(maxMessageBytes = 10)
        assembler.feed(read(WsCodec.encodeMasked(WsOpcode.TEXT, ByteArray(8), mask, isFin = false)))
        assertEquals(WsClose.TOO_BIG, closeCode(WsCodec.encodeMasked(WsOpcode.CONTINUATION, ByteArray(8), mask), assembler))
    }

    @Test
    fun `a close frame carries its code and reason, an empty one is normal`() {
        val close = WsAssembler().feed(read(WsCodec.encodeMasked(WsOpcode.CLOSE, byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray(), mask))) as WsMessage.Close
        assertEquals(1000, close.code)
        assertEquals("bye", close.reason)
        val empty = WsAssembler().feed(read(WsCodec.encodeMasked(WsOpcode.CLOSE, ByteArray(0), mask))) as WsMessage.Close
        assertEquals(1000, empty.code)
    }

    @Test
    fun `a truncated stream is end of file, not a protocol error`() {
        val bytes = WsCodec.encodeMasked(WsOpcode.TEXT, "Hello".toByteArray(), mask)
        try {
            read(bytes.copyOf(bytes.size - 2))
            fail("accepted")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun `the handshake accepts a browser request and refuses the rest`() {
        fun request(vararg headers: String) = HttpParser.parse(("GET /ws HTTP/1.1\r\nHost: phone:8420\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n"))
        val good = request("Upgrade: websocket", "Connection: keep-alive, Upgrade", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Origin: https://phone:8420")
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsHandshake.accept(good))
        fun status(r: HttpRequest): Int = try {
            WsHandshake.accept(r)
            fail("accepted")
        } catch (e: HttpError) {
            e.status
        }
        assertEquals(426, status(request("Connection: Upgrade", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")))
        assertEquals(400, status(request("Upgrade: websocket", "Connection: keep-alive", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")))
        assertEquals(400, status(request("Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Version: 8", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")))
        assertEquals(400, status(request("Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Version: 13")))
        assertEquals(400, status(request("Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Key: short")))
        assertEquals(403, status(request("Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Origin: https://evil.example")))
    }

    @Test
    fun `cidr membership covers v4, v6 and mapped addresses`() {
        val overlay = Cidr.parse("10.42.0.0/16") ?: fail("parse")
        assertTrue(overlay.contains(java.net.InetAddress.getByName("10.42.7.1")))
        assertTrue(!overlay.contains(java.net.InetAddress.getByName("10.43.0.1")))
        assertTrue(overlay.contains(java.net.InetAddress.getByName("::ffff:10.42.0.9")))
        val v6 = Cidr.parse("fd00::/8") ?: fail("parse")
        assertTrue(v6.contains(java.net.InetAddress.getByName("fd12::1")))
        assertTrue(!v6.contains(java.net.InetAddress.getByName("fe80::1")))
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertNull(Cidr.parse("not-an-address/8"))
        assertNull(Cidr.parse(""))
        val single = Cidr.parse("192.168.1.5") ?: fail("parse")
        assertTrue(single.contains(java.net.InetAddress.getByName("192.168.1.5")))
        assertTrue(!single.contains(java.net.InetAddress.getByName("192.168.1.6")))
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)
