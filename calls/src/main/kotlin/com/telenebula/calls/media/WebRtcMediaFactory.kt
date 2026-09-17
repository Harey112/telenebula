package com.telenebula.calls.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.telenebula.calls.CallMediaFactory
import com.telenebula.calls.LocalTracks
import com.telenebula.calls.PeerLink
import org.webrtc.EglBase

/** The libwebrtc implementation of the engine's media seam. */
class WebRtcMediaFactory(context: Context, private val runtime: WebRtcRuntime) : CallMediaFactory {
    private val app = context.applicationContext

    override val eglContext: EglBase.Context get() = runtime.eglBase.eglBaseContext

    /**
     * The audio device module fails to capture the microphone in total silence when RECORD_AUDIO
     * is missing (nothing throws), so it is checked up front — the UI is expected to have already
     * requested it, but a notification-triggered accept skips that path.
     */
    override fun openLocal(video: Boolean): LocalTracks {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) throw MediaUnavailableException("Microphone permission not granted")
        if (video && !hasPermission(Manifest.permission.CAMERA)) throw MediaUnavailableException("Camera permission not granted")
        val media = LocalMedia(app, runtime)
        if (video) {
            try {
                media.startCamera()
            } catch (e: Exception) {
                media.release()
                throw e
            }
        }
        return media
    }

    override fun openLink(listener: WebRtcSessionListener, local: LocalTracks): PeerLink {
        require(local is LocalMedia) { "local tracks must come from openLocal" }
        return WebRtcSession(runtime, listener).also { it.attach(local.audioTrack, local.videoTrack) }
    }

    override fun setVerbose(enabled: Boolean) = runtime.setVerbose(enabled)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
}
