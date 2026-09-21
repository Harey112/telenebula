package com.telenebula.web.net

import com.telenebula.web.wire.DexIdentity
import com.telenebula.web.wire.DexJson
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import org.w3c.dom.url.URL
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import org.w3c.files.Blob
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume
import kotlin.js.json

sealed interface LoginResult {
    data object Ok : LoginResult
    data object Wrong : LoginResult
    data object Locked : LoginResult
    data object ClientLimit : LoginResult
    data class Failed(val message: String) : LoginResult
}

sealed interface SessionResult {
    data class Active(val me: DexIdentity) : SessionResult
    data object None : SessionResult
    data class Failed(val message: String) : SessionResult
}

class UploadRequest(
    val peer: String,
    val name: String,
    val mime: String,
    val body: Blob,
    val replyTo: String?,
    val isCovered: Boolean,
    val isVoice: Boolean,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
)

/** A running upload; [abort] stops it and the awaiting caller sees [UploadOutcome.Cancelled]. */
class UploadHandle internal constructor(private val xhr: XMLHttpRequest) {
    fun abort() = xhr.abort()
}

sealed interface UploadOutcome {
    data object Done : UploadOutcome
    data object Cancelled : UploadOutcome
    data class Failed(val message: String) : UploadOutcome
}

@Serializable
private data class LoginBody(val username: String, val password: String)

@Serializable
private data class ErrorBody(val error: String? = null)

/** The phone's HTTP API; every failure is a value, nothing escapes as an exception. */
object Api {
    suspend fun session(): SessionResult = try {
        val response = window.fetch("/api/session", RequestInit(method = "GET", credentials = SAME_ORIGIN)).await()
        when (response.status.toInt()) {
            200 -> SessionResult.Active(DexJson.decodeFromString(DexIdentity.serializer(), response.text().await()))
            401 -> SessionResult.None
            else -> SessionResult.Failed("The phone answered ${response.status}")
        }
    } catch (e: Throwable) {
        SessionResult.Failed(e.message ?: "The phone did not answer")
    }

    suspend fun login(username: String, password: String): LoginResult = try {
        val body = DexJson.encodeToString(LoginBody.serializer(), LoginBody(username, password))
        val response = window.fetch(
            "/api/login",
            RequestInit(method = "POST", body = body, headers = json("Content-Type" to "application/json"), credentials = SAME_ORIGIN),
        ).await()
        when (response.status.toInt()) {
            204, 200 -> LoginResult.Ok
            401 -> LoginResult.Wrong
            429 -> LoginResult.Locked
            503 -> LoginResult.ClientLimit
            else -> LoginResult.Failed(errorOf(response) ?: "The phone answered ${response.status}")
        }
    } catch (e: Throwable) {
        LoginResult.Failed(e.message ?: "The phone did not answer")
    }

    suspend fun logout(): Boolean = try {
        window.fetch("/api/logout", RequestInit(method = "POST", credentials = SAME_ORIGIN)).await().ok
    } catch (e: Throwable) {
        false
    }

    private suspend fun errorOf(response: Response): String? = try {
        DexJson.decodeFromString(ErrorBody.serializer(), response.text().await()).error
    } catch (e: Throwable) {
        null
    }

    fun attachmentUrl(messageId: String): String = "/a/${encodeURIComponent(messageId)}"

    fun uploadUrl(request: UploadRequest): String {
        val url = URL("/a", window.location.origin)
        url.searchParams.set("peer", request.peer)
        url.searchParams.set("name", request.name)
        url.searchParams.set("mime", request.mime)
        request.replyTo?.let { url.searchParams.set("reply", it) }
        if (request.isCovered) url.searchParams.set("cover", "1")
        if (request.isVoice) url.searchParams.set("voice", "1")
        request.durationMs?.let { url.searchParams.set("durationMs", it.toString()) }
        request.width?.let { url.searchParams.set("width", it.toString()) }
        request.height?.let { url.searchParams.set("height", it.toString()) }
        return url.pathname + url.search
    }

    /** Streams the blob; [onProgress] gets 0..100. */
    suspend fun upload(request: UploadRequest, onStart: (UploadHandle) -> Unit, onProgress: (Int) -> Unit): UploadOutcome =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            var isSettled = false
            fun settle(outcome: UploadOutcome) {
                if (isSettled) return
                isSettled = true
                cont.resume(outcome)
            }
            xhr.upload.onprogress = { e ->
                val total = e.total.toDouble()
                if (e.lengthComputable && total > 0.0) onProgress(((e.loaded.toDouble() / total) * 100).toInt().coerceIn(0, 100))
            }
            xhr.onload = {
                if (xhr.status.toInt() == 201 || xhr.status.toInt() == 200) settle(UploadOutcome.Done)
                else settle(UploadOutcome.Failed(uploadError(xhr)))
            }
            xhr.onerror = { settle(UploadOutcome.Failed("Upload failed")) }
            xhr.onabort = { settle(UploadOutcome.Cancelled) }
            xhr.ontimeout = { settle(UploadOutcome.Failed("Upload timed out")) }
            cont.invokeOnCancellation { xhr.abort() }
            try {
                xhr.open("POST", uploadUrl(request))
                xhr.setRequestHeader("Content-Type", request.mime.ifBlank { "application/octet-stream" })
                onStart(UploadHandle(xhr))
                xhr.send(request.body)
            } catch (e: Throwable) {
                settle(UploadOutcome.Failed(e.message ?: "Upload failed"))
            }
        }

    private fun uploadError(xhr: XMLHttpRequest): String = when (xhr.status.toInt()) {
        401 -> "Not logged in"
        413 -> "That file is too large for Dex"
        else -> try {
            DexJson.decodeFromString(ErrorBody.serializer(), xhr.responseText).error ?: "The phone answered ${xhr.status}"
        } catch (e: Throwable) {
            "The phone answered ${xhr.status}"
        }
    }
}

external fun encodeURIComponent(value: String): String

private val SAME_ORIGIN: RequestCredentials = "same-origin".unsafeCast<RequestCredentials>()
