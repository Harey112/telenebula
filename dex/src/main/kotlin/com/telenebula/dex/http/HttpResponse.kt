package com.telenebula.dex.http

import com.telenebula.dex.Limits
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder

/** One satisfiable `Range: bytes=` request. */
class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

sealed interface RangeResult {
    data object None : RangeResult
    class Satisfiable(val range: ByteRange) : RangeResult
    data object Unsatisfiable : RangeResult
}

/** RFC 7233 single-range parsing; a multi-range or malformed header is [RangeResult.Unsatisfiable]. */
object Ranges {
    fun parse(header: String?, totalLength: Long): RangeResult {
        if (header == null) return RangeResult.None
        val spec = header.trim()
        if (!spec.startsWith("bytes=")) return RangeResult.Unsatisfiable
        val body = spec.removePrefix("bytes=").trim()
        if (body.isEmpty() || ',' in body) return RangeResult.Unsatisfiable
        val dash = body.indexOf('-')
        if (dash < 0) return RangeResult.Unsatisfiable
        val first = body.substring(0, dash).trim()
        val last = body.substring(dash + 1).trim()
        if (totalLength <= 0) return RangeResult.Unsatisfiable
        return when {
            first.isEmpty() -> {
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return RangeResult.Unsatisfiable
                val start = (totalLength - suffix).coerceAtLeast(0)
                RangeResult.Satisfiable(ByteRange(start, totalLength - 1))
            }
            else -> {
                val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return RangeResult.Unsatisfiable
                if (start >= totalLength) return RangeResult.Unsatisfiable
                val end = if (last.isEmpty()) totalLength - 1 else last.toLongOrNull()?.takeIf { it >= start } ?: return RangeResult.Unsatisfiable
                RangeResult.Satisfiable(ByteRange(start, minOf(end, totalLength - 1)))
            }
        }
    }
}

sealed interface HttpBody {
    data object Empty : HttpBody
    class Bytes(val bytes: ByteArray) : HttpBody
    /** [open] yields a stream already positioned at the first byte to send; [length] bytes are copied from it. */
    class Stream(val length: Long, val open: () -> InputStream) : HttpBody
}

class HttpResponse(
    val status: Int,
    val body: HttpBody = HttpBody.Empty,
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    /** false forces `Connection: close`, e.g. after a body the server did not read to its end */
    val isKeepAliveAllowed: Boolean = true,
) {
    companion object {
        fun empty(status: Int, vararg headers: Pair<String, String>) = HttpResponse(status, HttpBody.Empty, null, headers.toList())

        fun text(status: Int, text: String, contentType: String = "text/plain; charset=utf-8", vararg headers: Pair<String, String>) =
            HttpResponse(status, HttpBody.Bytes(text.toByteArray(Charsets.UTF_8)), contentType, headers.toList())

        fun json(status: Int, json: String, vararg headers: Pair<String, String>) = text(status, json, "application/json; charset=utf-8", *headers)

        fun error(status: Int, message: String = reason(status)) = HttpResponse(
            status,
            HttpBody.Bytes(message.toByteArray(Charsets.UTF_8)),
            "text/plain; charset=utf-8",
            emptyList(),
            isKeepAliveAllowed = status < 500 && status != 431 && status != 413,
        )

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            206 -> "Partial Content"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"
            426 -> "Upgrade Required"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            505 -> "HTTP Version Not Supported"
            else -> "Status $status"
        }

        /** `filename*=UTF-8''…` per RFC 6266/5987, so any name survives the header. */
        fun contentDisposition(name: String, isInline: Boolean = true): String {
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "${if (isInline) "inline" else "attachment"}; filename*=UTF-8''$encoded"
        }
    }
}

/** Writes responses; every response carries the security headers and an exact `Content-Length`. */
object HttpWriter {
    private const val CSP = "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self' wss:; script-src 'self'; style-src 'self' 'unsafe-inline'"

    fun write(out: OutputStream, response: HttpResponse, isHead: Boolean, isKeepAlive: Boolean) {
        val length = when (val b = response.body) {
            HttpBody.Empty -> 0L
            is HttpBody.Bytes -> b.bytes.size.toLong()
            is HttpBody.Stream -> b.length
        }
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(HttpResponse.reason(response.status)).append("\r\n")
        head.append("Content-Length: ").append(length).append("\r\n")
        response.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
        head.append("Connection: ").append(if (isKeepAlive) "keep-alive" else "close").append("\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("X-Frame-Options: DENY\r\n")
        head.append("Content-Security-Policy: ").append(CSP).append("\r\n")
        if (response.headers.none { it.first.equals("Cache-Control", ignoreCase = true) }) head.append("Cache-Control: no-store\r\n")
        for ((name, value) in response.headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!isHead) {
            when (val b = response.body) {
                HttpBody.Empty -> Unit
                is HttpBody.Bytes -> out.write(b.bytes)
                is HttpBody.Stream -> b.open().use { copy(it, out, b.length) }
            }
        }
        out.flush()
    }

    private fun copy(from: InputStream, to: OutputStream, length: Long) {
        val buffer = ByteArray(Limits.STREAM_CHUNK_BYTES)
        var left = length
        while (left > 0) {
            val n = from.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw java.io.EOFException("source ended before its declared length")
            to.write(buffer, 0, n)
            left -= n
        }
    }
}
