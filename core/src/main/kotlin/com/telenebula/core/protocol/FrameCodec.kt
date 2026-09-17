package com.telenebula.core.protocol

import com.telenebula.core.CoreException
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeFrom
import com.telenebula.core.model.EnvelopeType
import kotlinx.serialization.json.Json

/**
 * The v1 wire format: a 4-byte big-endian length prefix followed by the UTF-8 JSON envelope.
 * Nebula (Noise_IK) is the transport security layer, so nothing here encrypts or authenticates —
 * the peer's identity comes from the socket address.
 */
object FrameCodec {
    const val PROTOCOL_VERSION = 1

    /** Hard cap per frame. Attachment chunks are sized to stay well under it. */
    const val MAX_FRAME_BYTES = 256 * 1024

    /**
     * `encodeDefaults` keeps v/type/id/from/ts on every frame (they are not optional in the
     * protocol) while `explicitNulls` leaves every absent field out, and an unknown `type`
     * coerces to [EnvelopeType.UNKNOWN] instead of failing the whole frame.
     */
    val WireJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }


    fun encode(envelope: Envelope): ByteArray {
        val payload = WireJson.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_FRAME_BYTES) {
            throw CoreException.protocol("frame exceeds cap: ${payload.size} > $MAX_FRAME_BYTES")
        }
        val frame = ByteArray(4 + payload.size)
        writeLength(frame, payload.size)
        payload.copyInto(frame, 4)
        return frame
    }

    private fun writeLength(target: ByteArray, length: Int) {
        target[0] = (length ushr 24).toByte()
        target[1] = (length ushr 16).toByte()
        target[2] = (length ushr 8).toByte()
        target[3] = length.toByte()
    }
}

/**
 * Incremental frame reader. Bounded by construction: the buffer never holds more than one
 * capped frame plus one socket read, and a peer announcing an oversized frame is a protocol
 * error that drops the connection.
 */
class FrameParser {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    /** Appends `count` bytes of `data` and returns every complete envelope they finish. */
    fun feed(data: ByteArray, count: Int = data.size): List<Envelope> {
        append(data, count)
        var start = 0
        var out: MutableList<Envelope>? = null
        while (length - start >= 4) {
            val frameLength = readLength(start)
            // negative when the peer announced a length past 2^31, which is oversized either way
            if (frameLength < 0 || frameLength > FrameCodec.MAX_FRAME_BYTES) {
                throw CoreException.protocol("peer announced oversized frame: $frameLength")
            }
            if (length - start - 4 < frameLength) break
            val json = String(buffer, start + 4, frameLength, Charsets.UTF_8)
            val envelope = try {
                FrameCodec.WireJson.decodeFromString(Envelope.serializer(), json)
            } catch (e: Exception) {
                throw CoreException.protocol("malformed envelope: ${e.message}")
            }
            (out ?: ArrayList<Envelope>(4).also { out = it }).add(envelope)
            start += 4 + frameLength
        }
        if (start > 0) {
            buffer.copyInto(buffer, 0, start, length)
            length -= start
        }
        return out ?: emptyList()
    }

    private fun append(data: ByteArray, count: Int) {
        if (length + count > buffer.size) {
            var capacity = buffer.size
            while (capacity < length + count) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        data.copyInto(buffer, length, 0, count)
        length += count
    }

    private fun readLength(at: Int): Int =
        ((buffer[at].toInt() and 0xFF) shl 24) or
            ((buffer[at + 1].toInt() and 0xFF) shl 16) or
            ((buffer[at + 2].toInt() and 0xFF) shl 8) or
            (buffer[at + 3].toInt() and 0xFF)

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}
