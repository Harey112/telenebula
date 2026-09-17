package com.telenebula.core.protocol

import com.telenebula.core.CoreException
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeFrom
import com.telenebula.core.model.EnvelopeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire format is fixed by v1 of the protocol: these are compatibility tests, not style ones. */
class FrameCodecTest {
    private fun sample(type: EnvelopeType) =
        Envelope(v = FrameCodec.PROTOCOL_VERSION, type = type, id = "id-1", from = EnvelopeFrom("fd00::2", "alice"), ts = 1, body = "hello world")

    private fun frameOf(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        return ByteArray(4 + payload.size).also {
            it[0] = (payload.size ushr 24).toByte()
            it[1] = (payload.size ushr 16).toByte()
            it[2] = (payload.size ushr 8).toByte()
            it[3] = payload.size.toByte()
            payload.copyInto(it, 4)
        }
    }

    @Test
    fun `a frame round trips`() {
        val frame = FrameCodec.encode(sample(EnvelopeType.MSG))
        val out = FrameParser().feed(frame)
        assertEquals(1, out.size)
        val first = out.single()
        assertEquals(EnvelopeType.MSG, first.type)
        assertEquals("hello world", first.body)
        assertEquals("fd00::2", first.from.ip)
        assertEquals(FrameCodec.PROTOCOL_VERSION, first.v)
    }

    @Test
    fun `split delivery and several frames in one read`() {
        val bytes = FrameCodec.encode(sample(EnvelopeType.MSG)) + FrameCodec.encode(sample(EnvelopeType.PING))
        val parser = FrameParser()
        var count = 0
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + 7, bytes.size))
            count += parser.feed(chunk).size
            offset += chunk.size
        }
        assertEquals(2, count)
    }

    @Test
    fun `a transfer error carries its reason and target`() {
        val envelope = sample(EnvelopeType.ATT_ERROR).copy(targetId = "m1", reason = "no-space")
        val json = FrameCodec.WireJson.encodeToString(Envelope.serializer(), envelope)
        assertTrue(json.contains("\"type\":\"att-error\""))
        assertTrue(json.contains("\"targetId\":\"m1\""))
        assertTrue(json.contains("\"reason\":\"no-space\""))
    }

    @Test
    fun `the wire spelling matches the protocol, not the Kotlin names`() {
        val envelope = sample(EnvelopeType.REACT).copy(targetId = "m1", emoji = "👍")
        val json = FrameCodec.WireJson.encodeToString(Envelope.serializer(), envelope)
        assertTrue(json.contains("\"type\":\"react\""))
        assertTrue(json.contains("\"targetId\":\"m1\""))
        assertTrue(!json.contains("target_id"))
        // absent fields are left out entirely rather than sent as null
        assertTrue(!json.contains("\"sdp\""))
    }

    /** A peer too old to know a frame must ignore it, not drop the connection. */
    @Test
    fun `an unknown type still parses`() {
        val out = FrameParser().feed(
            frameOf("""{"v":1,"type":"something-from-the-future","id":"x","from":{"ip":"a","name":"b"},"ts":1}"""),
        )
        assertEquals(EnvelopeType.UNKNOWN, out.single().type)
    }

    @Test
    fun `an explicit null candidate reads as end-of-candidates`() {
        val out = FrameParser().feed(
            frameOf("""{"v":1,"type":"call-ice","id":"x","from":{"ip":"a","name":"b"},"ts":1,"callId":"c","candidate":null}"""),
        )
        assertEquals(EnvelopeType.CALL_ICE, out.single().type)
        assertNull(out.single().candidate)
    }

    @Test
    fun `an oversized frame is rejected before it is read`() {
        val parser = FrameParser()
        val header = ByteArray(4)
        val announced = FrameCodec.MAX_FRAME_BYTES + 1
        header[0] = (announced ushr 24).toByte()
        header[1] = (announced ushr 16).toByte()
        header[2] = (announced ushr 8).toByte()
        header[3] = announced.toByte()
        assertThrows(CoreException::class.java) { parser.feed(header) }
    }

    @Test
    fun `a length past two gigabytes is oversized, not negative`() {
        assertThrows(CoreException::class.java) {
            FrameParser().feed(byteArrayOf(-1, -1, -1, -1))
        }
    }

    @Test
    fun `encoding refuses to put an oversized frame on the wire`() {
        val huge = sample(EnvelopeType.MSG).copy(body = "x".repeat(FrameCodec.MAX_FRAME_BYTES))
        assertThrows(CoreException::class.java) { FrameCodec.encode(huge) }
    }

    @Test
    fun `a malformed envelope is a protocol error`() {
        assertThrows(CoreException::class.java) { FrameParser().feed(frameOf("{not json")) }
    }
}
