package com.telenebula.dex.turn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** STUN/TURN message codec (RFC 5389, RFC 5766): only what a browser's ICE agent sends a relay. */
object Stun {
    const val HEADER_BYTES = 20
    const val MAGIC_COOKIE = 0x2112A442
    const val TRANSACTION_BYTES = 12

    const val METHOD_BINDING = 0x001
    const val METHOD_ALLOCATE = 0x003
    const val METHOD_REFRESH = 0x004
    const val METHOD_SEND = 0x006
    const val METHOD_DATA = 0x007
    const val METHOD_CREATE_PERMISSION = 0x008
    const val METHOD_CHANNEL_BIND = 0x009

    const val CLASS_REQUEST = 0
    const val CLASS_INDICATION = 1
    const val CLASS_SUCCESS = 2
    const val CLASS_ERROR = 3

    const val ATTR_MAPPED_ADDRESS = 0x0001
    const val ATTR_USERNAME = 0x0006
    const val ATTR_MESSAGE_INTEGRITY = 0x0008
    const val ATTR_ERROR_CODE = 0x0009
    const val ATTR_UNKNOWN_ATTRIBUTES = 0x000A
    const val ATTR_CHANNEL_NUMBER = 0x000C
    const val ATTR_LIFETIME = 0x000D
    const val ATTR_XOR_PEER_ADDRESS = 0x0012
    const val ATTR_DATA = 0x0013
    const val ATTR_REALM = 0x0014
    const val ATTR_NONCE = 0x0015
    const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
    const val ATTR_EVEN_PORT = 0x0018
    const val ATTR_REQUESTED_TRANSPORT = 0x0019
    const val ATTR_DONT_FRAGMENT = 0x001A
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val ATTR_SOFTWARE = 0x8022
    const val ATTR_FINGERPRINT = 0x8028

    const val TRANSPORT_UDP = 17
    const val SOFTWARE_NAME = "TeleNebula Dex"

    /** The two high bits are zero in every STUN message; 0x40..0x7F is ChannelData (RFC 5766 §11.4). */
    fun isChannelData(first: Byte): Boolean = (first.toInt() and 0xC0) == 0x40

    fun isStun(first: Byte): Boolean = (first.toInt() and 0xC0) == 0

    class Attribute(val type: Int, val value: ByteArray)

    class Message(val method: Int, val cls: Int, val transactionId: ByteArray, val attributes: List<Attribute>) {
        fun first(type: Int): ByteArray? = attributes.firstOrNull { it.type == type }?.value
        fun all(type: Int): List<ByteArray> = attributes.filter { it.type == type }.map { it.value }
        val isRequest: Boolean get() = cls == CLASS_REQUEST
        val isIndication: Boolean get() = cls == CLASS_INDICATION
    }

    /** null when the bytes are not a well-formed STUN message; nothing is thrown for garbage. */
    fun parse(bytes: ByteArray, length: Int): Message? {
        if (length < HEADER_BYTES || length > bytes.size) return null
        val buf = ByteBuffer.wrap(bytes, 0, length)
        val typeField = buf.short.toInt() and 0xFFFF
        if (typeField and 0xC000 != 0) return null
        val bodyLength = buf.short.toInt() and 0xFFFF
        if (bodyLength % 4 != 0 || HEADER_BYTES + bodyLength != length) return null
        if (buf.int != MAGIC_COOKIE) return null
        val transactionId = ByteArray(TRANSACTION_BYTES).also { buf.get(it) }
        val attributes = ArrayList<Attribute>()
        while (buf.remaining() >= 4) {
            val type = buf.short.toInt() and 0xFFFF
            val valueLength = buf.short.toInt() and 0xFFFF
            if (valueLength > buf.remaining()) return null
            val value = ByteArray(valueLength).also { buf.get(it) }
            val padding = (4 - valueLength % 4) % 4
            if (padding > buf.remaining()) return null
            buf.position(buf.position() + padding)
            attributes.add(Attribute(type, value))
        }
        if (buf.hasRemaining()) return null
        return Message(methodOf(typeField), classOf(typeField), transactionId, attributes)
    }

    /** Method and class are interleaved in the 14-bit type (RFC 5389 §6). */
    fun methodOf(type: Int): Int = (type and 0x000F) or ((type and 0x00E0) shr 1) or ((type and 0x3E00) shr 2)

    fun classOf(type: Int): Int = ((type and 0x0010) shr 4) or ((type and 0x0100) shr 7)

    fun typeOf(method: Int, cls: Int): Int {
        val m = (method and 0x000F) or ((method and 0x0070) shl 1) or ((method and 0x0F80) shl 2)
        val c = ((cls and 0x1) shl 4) or ((cls and 0x2) shl 7)
        return m or c
    }

    /** Builds a message; call [Builder.integrity] and [Builder.fingerprint] last, in that order. */
    class Builder(private val method: Int, private val cls: Int, private val transactionId: ByteArray) {
        private val attributes = ArrayList<Attribute>()

        fun add(type: Int, value: ByteArray): Builder {
            attributes.add(Attribute(type, value))
            return this
        }

        fun addInt(type: Int, value: Int): Builder = add(type, ByteBuffer.allocate(4).putInt(value).array())

        fun addString(type: Int, value: String): Builder = add(type, value.toByteArray(Charsets.UTF_8))

        fun xorAddress(type: Int, address: InetSocketAddress): Builder = add(type, encodeXorAddress(address, transactionId))

        fun errorCode(code: Int, reason: String): Builder {
            val text = reason.toByteArray(Charsets.UTF_8)
            val value = ByteArray(4 + text.size)
            value[2] = (code / 100).toByte()
            value[3] = (code % 100).toByte()
            System.arraycopy(text, 0, value, 4, text.size)
            return add(ATTR_ERROR_CODE, value)
        }

        fun software(): Builder = addString(ATTR_SOFTWARE, SOFTWARE_NAME)

        fun build(key: ByteArray? = null, withFingerprint: Boolean = true): ByteArray {
            var body = encodeAttributes()
            if (key != null) {
                val macInput = header(body.size + 24) + body
                body += encodeAttribute(ATTR_MESSAGE_INTEGRITY, hmacSha1(key, macInput))
            }
            if (withFingerprint) {
                val crcInput = header(body.size + 8) + body
                val crc = CRC32().apply { update(crcInput) }.value.toInt() xor 0x5354554E
                body += encodeAttribute(ATTR_FINGERPRINT, ByteBuffer.allocate(4).putInt(crc).array())
            }
            return header(body.size) + body
        }

        private fun header(bodyLength: Int): ByteArray = ByteBuffer.allocate(HEADER_BYTES)
            .putShort(typeOf(method, cls).toShort())
            .putShort(bodyLength.toShort())
            .putInt(MAGIC_COOKIE)
            .put(transactionId)
            .array()

        private fun encodeAttributes(): ByteArray {
            var out = ByteArray(0)
            for (a in attributes) out += encodeAttribute(a.type, a.value)
            return out
        }
    }

    fun encodeAttribute(type: Int, value: ByteArray): ByteArray {
        val padded = (value.size + 3) / 4 * 4
        val out = ByteBuffer.allocate(4 + padded)
        out.putShort(type.toShort()).putShort(value.size.toShort()).put(value)
        return out.array()
    }

    fun encodeXorAddress(address: InetSocketAddress, transactionId: ByteArray): ByteArray {
        val raw = address.address.address
        val family = if (raw.size == 4) 0x01 else 0x02
        val out = ByteBuffer.allocate(4 + raw.size)
        out.put(0).put(family.toByte()).putShort((address.port xor (MAGIC_COOKIE ushr 16)).toShort())
        val mask = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array() + transactionId
        for (i in raw.indices) out.put((raw[i].toInt() xor mask[i].toInt()).toByte())
        return out.array()
    }

    /** null for a malformed or unknown-family address. */
    fun decodeXorAddress(value: ByteArray, transactionId: ByteArray): InetSocketAddress? {
        if (value.size < 8) return null
        val family = value[1].toInt() and 0xFF
        val size = when (family) {
            0x01 -> 4
            0x02 -> 16
            else -> return null
        }
        if (value.size != 4 + size) return null
        val port = (((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)) xor (MAGIC_COOKIE ushr 16)
        val mask = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array() + transactionId
        val raw = ByteArray(size) { i -> (value[4 + i].toInt() xor mask[i].toInt()).toByte() }
        val address = try {
            InetAddress.getByAddress(raw)
        } catch (e: java.net.UnknownHostException) {
            return null
        }
        if (address !is Inet4Address && address !is Inet6Address) return null
        return InetSocketAddress(address, port)
    }

    /** Long-term credential key (RFC 5389 §15.4). */
    fun longTermKey(username: String, realm: String, password: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$username:$realm:$password".toByteArray(Charsets.UTF_8))

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(data)

    /**
     * Checks MESSAGE-INTEGRITY over the received bytes: the HMAC covers everything before the
     * attribute, with the length field counting up to and including it (RFC 5389 §15.4).
     */
    fun verifyIntegrity(bytes: ByteArray, length: Int, key: ByteArray): Boolean {
        val end = integrityEnd(bytes, length) ?: return false
        val expected = bytes.copyOfRange(end - 20, end)
        val input = bytes.copyOfRange(0, end - 24)
        val adjustedLength = end - HEADER_BYTES
        input[2] = (adjustedLength shr 8).toByte()
        input[3] = adjustedLength.toByte()
        return MessageDigest.isEqual(hmacSha1(key, input), expected)
    }

    /** Offset just past the MESSAGE-INTEGRITY attribute, or null when the message has none. */
    private fun integrityEnd(bytes: ByteArray, length: Int): Int? {
        var pos = HEADER_BYTES
        while (pos + 4 <= length) {
            val type = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            val valueLength = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val next = pos + 4 + (valueLength + 3) / 4 * 4
            if (next > length) return null
            if (type == ATTR_MESSAGE_INTEGRITY) return if (valueLength == 20) next else null
            pos = next
        }
        return null
    }

    fun readInt(value: ByteArray): Int? = if (value.size == 4) ByteBuffer.wrap(value).int else null

    /** ChannelData framing (RFC 5766 §11.4); over UDP no padding is required. */
    fun channelData(channel: Int, data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteBuffer.allocate(4 + length)
        out.putShort(channel.toShort()).putShort(length.toShort()).put(data, offset, length)
        return out.array()
    }

    fun isChannelNumber(n: Int): Boolean = n in 0x4000..0x7FFF
}
