package com.telenebula.dex.http

import com.telenebula.dex.Limits
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** The reader refused the peer's frame; [code] is the close status the peer hears (RFC 6455 §7.4). */
class WsCloseException(val code: Int, val reason: String) : Exception(reason)

class WsRawFrame(val opcode: Int, val isFin: Boolean, val payload: ByteArray)

sealed interface WsMessage {
    class Text(val text: String) : WsMessage
    class Ping(val payload: ByteArray) : WsMessage
    class Pong(val payload: ByteArray) : WsMessage
    class Close(val code: Int, val reason: String) : WsMessage
}

object WsOpcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

object WsClose {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val PROTOCOL_ERROR = 1002
    const val UNSUPPORTED_DATA = 1003
    const val BAD_PAYLOAD = 1007
    const val POLICY = 1008
    const val TOO_BIG = 1009
    const val INTERNAL = 1011
    const val TRY_AGAIN_LATER = 1013
}

/** RFC 6455 framing as the server sees it: client frames masked, server frames not. */
object WsCodec {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_CONTROL_PAYLOAD = 125

    fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((clientKey.trim() + GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun encode(opcode: Int, payload: ByteArray, isFin: Boolean = true): ByteArray {
        require(opcode in 0..0xF) { "bad opcode" }
        if (opcode >= WsOpcode.CLOSE) require(payload.size <= MAX_CONTROL_PAYLOAD) { "control payload too long" }
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write((if (isFin) 0x80 else 0) or opcode)
        when {
            payload.size <= 125 -> out.write(payload.size)
            payload.size <= 0xFFFF -> {
                out.write(126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(127)
                val len = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) out.write(((len ushr shift) and 0xFF).toInt())
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    fun encodeText(text: String): ByteArray = encode(WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8))

    fun encodeClose(code: Int, reason: String): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_CONTROL_PAYLOAD - 2) it.copyOf(MAX_CONTROL_PAYLOAD - 2) else it }
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = (code ushr 8).toByte()
        payload[1] = code.toByte()
        reasonBytes.copyInto(payload, 2)
        return encode(WsOpcode.CLOSE, payload)
    }

    /** A client frame the peer may need to send under test; the server never masks. */
    fun encodeMasked(opcode: Int, payload: ByteArray, mask: ByteArray, isFin: Boolean = true): ByteArray {
        require(mask.size == 4) { "mask is four bytes" }
        val unmasked = encode(opcode, payload, isFin)
        val out = ByteArrayOutputStream(unmasked.size + 4)
        out.write(unmasked[0].toInt())
        out.write(unmasked[1].toInt() or 0x80)
        val headerLength = when (unmasked[1].toInt() and 0x7F) {
            126 -> 4
            127 -> 10
            else -> 2
        }
        out.write(unmasked, 2, headerLength - 2)
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        return out.toByteArray()
    }

    /**
     * Reads one masked client frame. [EOFException] at a closed stream; [WsCloseException] for a
     * frame the protocol forbids. [maxPayload] bounds a single frame; the assembler bounds a message.
     */
    fun readFrame(input: InputStream, maxPayload: Int = Limits.MAX_WS_MESSAGE_BYTES): WsRawFrame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        if (b0 and 0x70 != 0) throw WsCloseException(WsClose.PROTOCOL_ERROR, "reserved bits set")
        val isFin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        val isMasked = b1 and 0x80 != 0
        if (!isMasked) throw WsCloseException(WsClose.PROTOCOL_ERROR, "client frame not masked")
        val isControl = opcode >= WsOpcode.CLOSE
        if (opcode !in setOf(WsOpcode.CONTINUATION, WsOpcode.TEXT, WsOpcode.BINARY, WsOpcode.CLOSE, WsOpcode.PING, WsOpcode.PONG)) {
            throw WsCloseException(WsClose.PROTOCOL_ERROR, "unknown opcode")
        }
        var length = (b1 and 0x7F).toLong()
        when (length) {
            126L -> length = ((readByte(input) shl 8) or readByte(input)).toLong()
            127L -> {
                length = 0
                for (i in 0 until 8) length = (length shl 8) or readByte(input).toLong()
                if (length < 0) throw WsCloseException(WsClose.PROTOCOL_ERROR, "bad length")
            }
        }
        if (isControl && (!isFin || length > MAX_CONTROL_PAYLOAD)) throw WsCloseException(WsClose.PROTOCOL_ERROR, "bad control frame")
        if (length > maxPayload) throw WsCloseException(WsClose.TOO_BIG, "frame too large")
        val mask = ByteArray(4).also { readFully(input, it) }
        val payload = ByteArray(length.toInt()).also { readFully(input, it) }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return WsRawFrame(opcode, isFin, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("socket closed")
        return b
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val n = input.read(into, offset, into.size - offset)
            if (n < 0) throw EOFException("socket closed")
            offset += n
        }
    }
}

/** Turns raw frames into messages: fragments join in order, control frames pass straight through. */
class WsAssembler(private val maxMessageBytes: Int = Limits.MAX_WS_MESSAGE_BYTES) {
    private var fragments: ByteArrayOutputStream? = null

    fun feed(frame: WsRawFrame): WsMessage? {
        when (frame.opcode) {
            WsOpcode.PING -> return WsMessage.Ping(frame.payload)
            WsOpcode.PONG -> return WsMessage.Pong(frame.payload)
            WsOpcode.CLOSE -> return parseClose(frame.payload)
            WsOpcode.BINARY -> throw WsCloseException(WsClose.UNSUPPORTED_DATA, "binary frames are not accepted")
            WsOpcode.TEXT -> {
                if (fragments != null) throw WsCloseException(WsClose.PROTOCOL_ERROR, "new message inside a fragmented one")
                if (frame.isFin) return WsMessage.Text(decode(frame.payload))
                fragments = ByteArrayOutputStream(frame.payload.size * 2).also { it.write(frame.payload) }
                return null
            }
            WsOpcode.CONTINUATION -> {
                val open = fragments ?: throw WsCloseException(WsClose.PROTOCOL_ERROR, "continuation with nothing to continue")
                if (open.size().toLong() + frame.payload.size > maxMessageBytes) {
                    fragments = null
                    throw WsCloseException(WsClose.TOO_BIG, "message too large")
                }
                open.write(frame.payload)
                if (!frame.isFin) return null
                fragments = null
                return WsMessage.Text(decode(open.toByteArray()))
            }
            else -> throw WsCloseException(WsClose.PROTOCOL_ERROR, "unknown opcode")
        }
    }

    private fun decode(bytes: ByteArray): String =
        Utf8.decodeOrNull(bytes) ?: throw WsCloseException(WsClose.BAD_PAYLOAD, "text is not UTF-8")

    private fun parseClose(payload: ByteArray): WsMessage.Close {
        if (payload.isEmpty()) return WsMessage.Close(WsClose.NORMAL, "")
        if (payload.size == 1) throw WsCloseException(WsClose.PROTOCOL_ERROR, "one-byte close code")
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (!isValidCloseCode(code)) throw WsCloseException(WsClose.PROTOCOL_ERROR, "bad close code")
        val reason = Utf8.decodeOrNull(payload.copyOfRange(2, payload.size)) ?: throw WsCloseException(WsClose.BAD_PAYLOAD, "close reason is not UTF-8")
        return WsMessage.Close(code, reason)
    }

    private fun isValidCloseCode(code: Int): Boolean = when {
        code in 1000..1003 || code in 1007..1011 || code in 1012..1014 -> true
        code in 3000..4999 -> true
        else -> false
    }
}

/** The RFC 6455 §4.2 opening handshake; the accept key on success, an [HttpError] otherwise. */
object WsHandshake {
    fun accept(request: HttpRequest): String {
        if (request.method != "GET") throw HttpError(405, "websocket needs GET")
        if (!request.isUpgrade) throw HttpError(426, "upgrade required")
        val connection = request.header("connection")?.lowercase().orEmpty()
        if (connection.split(',').none { it.trim() == "upgrade" }) throw HttpError(400, "connection must upgrade")
        if (request.header("sec-websocket-version")?.trim() != "13") throw HttpError(400, "websocket version 13 only")
        val key = request.header("sec-websocket-key")?.trim() ?: throw HttpError(400, "missing websocket key")
        val decoded = try {
            Base64.getDecoder().decode(key)
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, "bad websocket key")
        }
        if (decoded.size != 16) throw HttpError(400, "bad websocket key")
        val origin = request.header("origin")
        val host = request.header("host")
        if (origin != null && host != null && !isSameHost(origin, host)) throw HttpError(403, "origin mismatch")
        return WsCodec.acceptKey(key)
    }

    private fun isSameHost(origin: String, host: String): Boolean {
        val authority = origin.trim().substringAfter("://", "").substringBefore('/')
        return authority.isNotEmpty() && authority.equals(host.trim(), ignoreCase = true)
    }
}
