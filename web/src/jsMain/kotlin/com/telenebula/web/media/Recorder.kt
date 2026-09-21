package com.telenebula.web.media

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.files.Blob
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json

class Clip(val blob: Blob, val mime: String, val durationMs: Long)

/** A microphone clip through MediaRecorder; every missing API is a null result, never a throw. */
class Recorder {
    private var recorder: dynamic = null
    private var stream: dynamic = null
    private var chunks = ArrayList<Blob>()
    private var startedAt = 0L
    private var mime = ""

    companion object {
        val isSupported: Boolean get() = js("typeof MediaRecorder !== 'undefined' && typeof navigator !== 'undefined' && !!navigator.mediaDevices").unsafeCast<Boolean>()

        private val PREFERRED = listOf("audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus", "audio/ogg")

        fun supportedMime(): String? {
            if (!isSupported) return null
            for (m in PREFERRED) if (isTypeSupported(m)) return m
            return ""
        }

        private fun isTypeSupported(m: String): Boolean = try {
            js("MediaRecorder.isTypeSupported(m)").unsafeCast<Boolean>()
        } catch (e: Throwable) {
            false
        }

        fun extensionFor(mime: String): String = when {
            mime.startsWith("audio/webm") -> "webm"
            mime.startsWith("audio/mp4") -> "m4a"
            mime.startsWith("audio/ogg") -> "ogg"
            else -> "bin"
        }
    }

    val isRecording: Boolean get() = recorder != null

    /** null when the microphone or the API is not available. */
    suspend fun start(): Boolean {
        if (recorder != null) return true
        val wanted = supportedMime() ?: return false
        return try {
            val s = (window.navigator.asDynamic().mediaDevices.getUserMedia(json("audio" to true)) as Promise<dynamic>).await()
            val opts = if (wanted.isNotEmpty()) json("mimeType" to wanted) else json()
            val r: dynamic = newRecorder(s, opts)
            chunks = ArrayList()
            r.ondataavailable = { e: dynamic ->
                val data = e.data as? Blob
                if (data != null && data.size.toDouble() > 0) chunks.add(data)
                Unit
            }
            r.start(250)
            recorder = r
            stream = s
            mime = (r.mimeType as? String)?.takeIf { it.isNotEmpty() } ?: wanted.ifEmpty { "audio/webm" }
            startedAt = Date.now().toLong()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun newRecorder(stream: dynamic, opts: dynamic): dynamic = js("new MediaRecorder(stream, opts)")

    /** Ends the recording and resolves with the clip, or null when nothing was captured. */
    fun stop(onClip: (Clip?) -> Unit) {
        val r = recorder ?: run {
            onClip(null)
            return
        }
        recorder = null
        val duration = Date.now().toLong() - startedAt
        r.onstop = {
            releaseStream()
            val blob = Blob(chunks.toTypedArray(), org.w3c.files.BlobPropertyBag(type = mime.substringBefore(';')))
            onClip(if (blob.size.toDouble() > 0) Clip(blob, mime.substringBefore(';'), duration) else null)
            Unit
        }
        try {
            r.stop()
        } catch (e: Throwable) {
            releaseStream()
            onClip(null)
        }
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.onstop = null
            r.stop()
        } catch (e: Throwable) {
            // already stopped
        }
        releaseStream()
        chunks.clear()
    }

    private fun releaseStream() {
        val s = stream ?: return
        stream = null
        try {
            val tracks = s.getTracks().unsafeCast<Array<dynamic>>()
            for (t in tracks) t.stop()
        } catch (e: Throwable) {
            // nothing to release
        }
    }
}
