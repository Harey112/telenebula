package com.telenebula.calls.render

import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Attaches one renderer to a [VideoTrack] and detaches it again. `addSink`/`removeSink` are
 * thread-safe in libwebrtc, so no executor hop is needed; a track disposed meanwhile throws
 * and is ignored.
 */
class VideoTrackBinding(private val renderer: TextureVideoRenderer, private val egl: EglBase.Context?) {
    private var track: VideoTrack? = null

    fun bind(next: VideoTrack?) {
        if (next === track) return
        detach()
        if (next == null) return
        renderer.init(egl)
        track = next
        runCatching { next.addSink(renderer) }
    }

    fun detach() {
        val old = track ?: return
        track = null
        runCatching { old.removeSink(renderer) }
        renderer.clearImage()
    }

    fun release() {
        detach()
        renderer.release()
    }
}
