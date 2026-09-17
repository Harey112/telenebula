package com.telenebula.calls.media

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One libwebrtc factory and one EGL context per process, created on the first call. The EGL
 * context is shared with every renderer so decoded frames never leave the GPU.
 */
class WebRtcRuntime(private val context: Context) {
    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile
    private var isVerbose = false

    private val factoryDelegate = lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        applyLogging()
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        // With Android's network monitor on, libwebrtc binds every media socket to a network handle
        // and then to the any-address; for the nebula tun that yields a `[::]` host candidate, which
        // libwebrtc filters out before signaling — so no candidate ever crosses the overlay and only
        // same-LAN calls connect. Without the monitor, sockets bind to the interface's real address
        // and the kernel routes them, exactly like nebula's own UDP socket.
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }
        PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { adm.release() }
    }

    val factory: PeerConnectionFactory by factoryDelegate

    /**
     * libwebrtc's own log (networks, candidates, ICE) into logcat while the diagnostics switch is
     * on. Routing the Java-side log through the native filter cannot be undone within a process,
     * so "off" after "on" keeps warnings only until the app restarts; never enabled means the
     * library's default logcat output stays as it is.
     */
    fun setVerbose(enabled: Boolean) {
        isVerbose = enabled
        if (factoryDelegate.isInitialized()) applyLogging()
    }

    private var isRouted = false

    private fun applyLogging() {
        when {
            isVerbose -> {
                isRouted = true
                Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
            }
            isRouted -> Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        }
    }
}
