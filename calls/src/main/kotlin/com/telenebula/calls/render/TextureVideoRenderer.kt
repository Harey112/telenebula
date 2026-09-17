package com.telenebula.calls.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * WebRTC video sink rendered into a TextureView. Unlike RTCView's
 * SurfaceView, a TextureView is an ordinary view: parents clip it with
 * rounded outlines and it composes normally with other views (no z-order
 * holes, no fake corner masks).
 */
class TextureVideoRenderer(context: Context) :
  TextureView(context), VideoSink, TextureView.SurfaceTextureListener {

  private val eglRenderer = EglRenderer("NebulaVideo")
  private var isInitialized = false

  init {
    isOpaque = false
    surfaceTextureListener = this
  }

  fun init(sharedContext: EglBase.Context?) {
    if (isInitialized) return
    eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
    isInitialized = true
    surfaceTexture?.let { eglRenderer.createEglSurface(it) }
  }

  fun release() {
    if (!isInitialized) return
    eglRenderer.release()
    isInitialized = false
  }

  fun setMirror(mirror: Boolean) = eglRenderer.setMirror(mirror)

  fun clearImage() = eglRenderer.clearImage()

  override fun onFrame(frame: VideoFrame) = eglRenderer.onFrame(frame)

  // Layout aspect = view aspect makes EglRenderer crop the frame to fill the
  // view (CSS object-fit: cover) — the only mode the app uses.
  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    val w = right - left
    val h = bottom - top
    if (w > 0 && h > 0) eglRenderer.setLayoutAspectRatio(w / h.toFloat())
  }

  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    if (isInitialized) eglRenderer.createEglSurface(surface)
  }

  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    val done = CountDownLatch(1)
    eglRenderer.releaseEglSurface { done.countDown() }
    org.webrtc.ThreadUtils.awaitUninterruptibly(done)
    return true
  }
}
