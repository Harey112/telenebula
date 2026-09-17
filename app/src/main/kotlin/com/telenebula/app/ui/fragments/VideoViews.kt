package com.telenebula.app.ui.fragments

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.telenebula.calls.render.TextureVideoRenderer
import com.telenebula.calls.render.VideoTrackBinding
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Small video tile (self-view, floating window): a TextureView renderer, so Compose can clip
 * its corners and layer it freely. Sinks attach on bind and detach on release.
 */
@Composable
fun VideoTile(track: VideoTrack?, egl: EglBase.Context, modifier: Modifier = Modifier, mirror: Boolean = false, cornerRadius: Dp = 0.dp) {
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        factory = { ctx -> TextureVideoRenderer(ctx).also { it.tag = TileState(VideoTrackBinding(it, egl)) } },
        update = { view ->
            val state = view.tag as TileState
            state.binding.bind(track)
            // setMirror posts to the render thread; the call screen recomposes every second, so only a change goes through
            if (state.mirror != mirror) {
                state.mirror = mirror
                view.setMirror(mirror)
            }
        },
        onRelease = { view -> (view.tag as TileState).binding.release() },
    )
}

private class TileState(val binding: VideoTrackBinding) {
    var mirror: Boolean? = null
}

private class SurfaceBinding(private val view: SurfaceViewRenderer) {
    private var track: VideoTrack? = null
    var mirror: Boolean? = null

    fun bind(next: VideoTrack?) {
        if (next === track) return
        track?.let { runCatching { it.removeSink(view) } }
        track = next
        next?.let { runCatching { it.addSink(view) } }
    }

    fun setMirror(next: Boolean) {
        if (mirror == next) return
        mirror = next
        view.setMirror(next)
    }

    fun release() {
        bind(null)
        view.release()
    }
}

/** Full-screen remote video: the cheap SurfaceView path, cropped to fill; controls draw on top. */
@Composable
fun RemoteVideoView(track: VideoTrack?, egl: EglBase.Context, modifier: Modifier = Modifier, mirror: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                tag = SurfaceBinding(this)
            }
        },
        update = { view ->
            val binding = view.tag as SurfaceBinding
            binding.bind(track)
            binding.setMirror(mirror)
        },
        onRelease = { view -> (view.tag as SurfaceBinding).release() },
    )
}
