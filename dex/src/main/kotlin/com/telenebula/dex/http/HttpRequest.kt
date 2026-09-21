package com.telenebula.dex.http

import com.telenebula.dex.Limits
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** A request the parser refused; [status] is what the browser hears. */
class HttpError(val status: Int, message: String) : Exception(message)

class HttpRequest(
    val method: String,
    /** percent-decoded, always starting with `/` */
    val path: String,
    val query: Map<String, String>,
    /** lower-cased names; repeated headers joined with `, ` */
    val headers: Map<String, String>,
    val contentLength: Long,
    val isKeepAlive: Boolean,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val isUpgrade: Boolean get() = header("upgrade")?.equals("websocket", ignoreCase = true) == true

    /** The `dex` cookie, if the browser sent one. */
    fun cookie(name: String): String? {
        val raw = header("cookie") ?: return null
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
        }
        return null
    }
}

/** RFC 7230 request head parsing; every malformed input is one [HttpError]. */
object HttpParser {
    private val METHODS = setOf("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "PATCH")
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    /**
     * Reads one request head from [input]. Null at a clean end of stream before any byte; a stream
     * that ends mid-head is a 400.
     */
    fun read(input: InputStream, maxHeadBytes: Int = Limits.MAX_HEAD_BYTES): HttpRequest? {
        val head = readHead(input, maxHeadBytes) ?: return null
        return parse(head)
    }

    fun parse(head: String): HttpRequest {
        val lines = head.split("\r\n").filterNot { it.isEmpty() }
        if (lines.isEmpty()) throw HttpError(400, "empty request")
        val parts = lines[0].split(' ')
        if (parts.size != 3) throw HttpError(400, "bad request line")
        val (method, target, version) = parts
        if (method !in METHODS) throw HttpError(405, "unknown method")
        val isHttp11 = when (version) {
            "HTTP/1.1" -> true
            "HTTP/1.0" -> false
            else -> throw HttpError(505, "unsupported version")
        }
        if (!target.startsWith("/") || target.length > maxTargetChars) throw HttpError(400, "bad target")
        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val rawQuery = if (q >= 0) target.substring(q + 1) else ""
        val path = normalisePath(percentDecode(rawPath) ?: throw HttpError(400, "bad path encoding"))
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "bad header")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpError(400, "bad header name")
            val value = line.substring(colon + 1).trim()
            if (value.any { it < ' ' && it != '\t' }) throw HttpError(400, "bad header value")
            val key = name.lowercase()
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(411, "length required")
        val contentLength = headers["content-length"]?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "bad content length")
        } ?: 0L
        val connection = headers["connection"]?.lowercase().orEmpty()
        val isKeepAlive = if (isHttp11) "close" !in connection else "keep-alive" in connection
        return HttpRequest(method, path, parseQuery(rawQuery), headers, contentLength, isKeepAlive)
    }

    /** Collapses `.` and `..` segments so no path the router sees can climb; a climb past the root is a 400. */
    fun normalisePath(decoded: String): String {
        if (decoded.any { it < ' ' }) throw HttpError(400, "control character in path")
        val out = ArrayList<String>()
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) throw HttpError(400, "path climbs past root") else out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return "/" + out.joinToString("/")
    }

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = percentDecode((if (eq >= 0) pair.substring(0, eq) else pair).replace('+', ' ')) ?: continue
            val value = percentDecode((if (eq >= 0) pair.substring(eq + 1) else "").replace('+', ' ')) ?: continue
            if (key.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        return out
    }

    /** Null for a dangling or non-hex escape, or bytes that are not UTF-8. */
    fun percentDecode(text: String): String? {
        if ('%' !in text) return if (text.all { it.code < 128 }) text else text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return Utf8.decodeOrNull(bytes.toByteArray())
    }

    private fun readHead(input: InputStream, maxHeadBytes: Int): String? {
        val buffer = ByteArrayOutputStream(1024)
        var matched = 0
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (count == 0) return null
                throw HttpError(400, "connection closed mid-request")
            }
            count += 1
            if (count > maxHeadBytes) throw HttpError(431, "request head too large")
            buffer.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        return Utf8.decodeOrNull(buffer.toByteArray()) ?: throw HttpError(400, "request head is not text")
    }

    private const val maxTargetChars = 8 * 1024
}

/** A bounded reader over a request body; never reads past [remaining] and reports a short body. */
class BodyReader(private val input: InputStream, length: Long) {
    var remaining: Long = length
        private set

    val isDrained: Boolean get() = remaining == 0L

    /** The whole body, at most [max] bytes; longer bodies are a 413 before a byte is read. */
    fun readAll(max: Int): ByteArray {
        if (remaining > max) throw HttpError(413, "body too large")
        val out = ByteArray(remaining.toInt())
        var offset = 0
        while (offset < out.size) {
            val n = input.read(out, offset, out.size - offset)
            if (n < 0) throw EOFException("body ended early")
            offset += n
            remaining -= n
        }
        return out
    }

    /** Copies the body into [sink] in bounded chunks; throws [EOFException] on a short body. */
    fun copyTo(sink: java.io.OutputStream, chunk: Int = Limits.STREAM_CHUNK_BYTES) {
        val buffer = ByteArray(chunk)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException("body ended early")
            sink.write(buffer, 0, n)
            remaining -= n
        }
    }
}

object Utf8 {
    fun decodeOrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }
}
