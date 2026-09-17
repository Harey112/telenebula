package com.telenebula.calls.media

import android.content.Context
import com.telenebula.calls.LocalTracks
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class MediaUnavailableException(message: String) : Exception(message)

/**
 * The microphone track for the whole call and the camera while it is on. Turning the camera off
 * disposes capturer, helper, source and track so the camera light goes out and no frozen frame
 * can be sent.
 */
class LocalMedia(private val context: Context, private val runtime: WebRtcRuntime) : LocalTracks {
    private val factory: PeerConnectionFactory get() = runtime.factory
    private var audioSource: AudioSource? = null
    val audioTrack: AudioTrack

    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    override var videoTrack: VideoTrack? = null
        private set
    // written on libwebrtc's camera thread, read on the engine's
    @Volatile
    override var isFrontCamera: Boolean = true
        private set

    init {
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, source)
    }

    /** Opens the front camera (any camera when there is none) and returns the live track. */
    override fun startCamera(): VideoTrack {
        videoTrack?.let { return it }
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val name = front ?: names.firstOrNull() ?: throw MediaUnavailableException("No camera")
        isFrontCamera = front != null
        val cameraCapturer = enumerator.createCapturer(name, null) ?: throw MediaUnavailableException("Camera busy")
        val textureHelper = SurfaceTextureHelper.create(CAPTURE_THREAD, runtime.eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        cameraCapturer.initialize(textureHelper, context, source.capturerObserver)
        try {
            cameraCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        } catch (e: RuntimeException) {
            cameraCapturer.dispose()
            textureHelper.dispose()
            source.dispose()
            throw MediaUnavailableException(e.message ?: "Camera failed to start")
        }
        capturer = cameraCapturer
        helper = textureHelper
        videoSource = source
        return factory.createVideoTrack(VIDEO_TRACK_ID, source).also { videoTrack = it }
    }

    override fun stopCamera() {
        val track = videoTrack ?: return
        videoTrack = null
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        helper?.dispose()
        helper = null
        track.dispose()
        videoSource?.dispose()
        videoSource = null
    }

    override fun switchCamera(onSwitched: (isFront: Boolean) -> Unit) {
        capturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    isFrontCamera = isFront
                    onSwitched(isFront)
                }

                override fun onCameraSwitchError(error: String?) = Unit
            },
        )
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        audioTrack.setEnabled(enabled)
    }

    override fun release() {
        stopCamera()
        audioTrack.dispose()
        audioSource?.dispose()
        audioSource = null
    }

    private companion object {
        const val AUDIO_TRACK_ID = "tn-audio0"
        const val VIDEO_TRACK_ID = "tn-video0"
        const val CAPTURE_THREAD = "tn-camera"
        // react-native-webrtc's getUserMedia default
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720
        const val CAPTURE_FPS = 30
    }
}
