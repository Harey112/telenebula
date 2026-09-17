package com.telenebula.calls.system

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.telenebula.calls.CallAction
import com.telenebula.calls.CallsConfig
import com.telenebula.calls.render.TextureVideoRenderer
import com.telenebula.calls.render.VideoTrackBinding
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.roundToInt

data class FloatingCallState(
  val remoteTrack: VideoTrack?,
  val localTrack: VideoTrack?,
  val remoteCamOn: Boolean,
  val localCamOn: Boolean,
  /** the self-view mirrors only for the front camera */
  val isLocalFrontCamera: Boolean,
  val peerName: String,
  val status: String,
)

/**
 * The minimized-video-call window drawn OVER OTHER APPS (SYSTEM_ALERT_WINDOW):
 * a rounded card with both parties side by side, draggable, snapping to the
 * nearest edge; a tap brings the app to the call screen. Because an overlay
 * also draws over this app, the in-app minimized state and the
 * outside-the-app state share this one window. Main thread only.
 */
object FloatingCallWindow {
  private const val WIDTH_DP = 220f
  private const val HEIGHT_DP = 132f
  private const val RADIUS_DP = 16f
  private const val MARGIN_DP = 12f
  private const val CARD_COLOR = 0xFF0E141B.toInt()
  private const val TILE_COLOR = 0xFF151B22.toInt()
  private val AVATAR_PALETTE =
    intArrayOf(0xFFE17076.toInt(), 0xFF7BC862.toInt(), 0xFF65AADD.toInt(), 0xFFA695E7.toInt(),
      0xFFEE7AAE.toInt(), 0xFF6EC9CB.toInt(), 0xFFFAA774.toInt())

  private var windowManager: WindowManager? = null
  private var root: FrameLayout? = null
  private var params: WindowManager.LayoutParams? = null
  private var remoteTile: Tile? = null
  private var localTile: Tile? = null
  private var label: TextView? = null
  private var egl: EglBase.Context? = null

  fun isShowing(): Boolean = root != null

  fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

  /** [egl] is the call engine's shared context so the tiles render the live tracks. */
  fun show(context: Context, egl: EglBase.Context?, state: FloatingCallState) {
    this.egl = egl
    if (root == null) {
      val app = context.applicationContext
      if (!canDrawOverlays(app)) return
      build(app)
    }
    apply(state)
  }

  fun update(state: FloatingCallState) {
    if (root != null) apply(state)
  }

  fun hide() {
    val view = root ?: return
    remoteTile?.release()
    localTile?.release()
    try {
      windowManager?.removeViewImmediate(view)
    } catch (e: Exception) {
      // already detached
    }
    root = null
    remoteTile = null
    localTile = null
    label = null
    params = null
    egl = null
  }

  private fun apply(state: FloatingCallState) {
    remoteTile?.set(state.remoteTrack, state.remoteCamOn, state.peerName, mirror = false)
    localTile?.set(state.localTrack, state.localCamOn, "You", mirror = state.isLocalFrontCamera)
    label?.text = state.status
  }

  private fun build(context: Context) {
    val density = context.resources.displayMetrics.density
    val width = (WIDTH_DP * density).roundToInt()
    val height = (HEIGHT_DP * density).roundToInt()
    val margin = (MARGIN_DP * density).roundToInt()
    val radius = RADIUS_DP * density

    val card = FrameLayout(context).apply {
      background = GradientDrawable().also {
        it.cornerRadius = radius
        it.setColor(CARD_COLOR)
      }
      outlineProvider = ViewOutlineProvider.BACKGROUND
      clipToOutline = true
      elevation = 8f * density
    }
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val remote = Tile(context, egl)
    val local = Tile(context, egl)
    val half = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    row.addView(remote.view, half)
    row.addView(local.view, LinearLayout.LayoutParams(half))
    card.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

    val status = TextView(context).apply {
      setTextColor(0xB0FFFFFF.toInt())
      textSize = 11f
      maxLines = 1
    }
    card.addView(
      status,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
        it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        it.bottomMargin = (6 * density).roundToInt()
      },
    )

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val screen = context.resources.displayMetrics
    val lp = WindowManager.LayoutParams(
      width,
      height,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = screen.widthPixels - width - margin
      y = (58 * density).roundToInt() + margin
    }

    attachDrag(card, wm, lp, context, width, height, margin)
    wm.addView(card, lp)

    windowManager = wm
    root = card
    params = lp
    remoteTile = remote
    localTile = local
    label = status
  }

  private fun attachDrag(
    view: View,
    wm: WindowManager,
    lp: WindowManager.LayoutParams,
    context: Context,
    width: Int,
    height: Int,
    margin: Int,
  ) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var isDragging = false
    var animator: ValueAnimator? = null

    view.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          animator?.cancel()
          downX = event.rawX
          downY = event.rawY
          startX = lp.x
          startY = lp.y
          isDragging = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - downX
          val dy = event.rawY - downY
          if (!isDragging && (abs(dx) > slop || abs(dy) > slop)) isDragging = true
          if (isDragging) {
            lp.x = (startX + dx).roundToInt()
            lp.y = (startY + dy).roundToInt()
            safeUpdate(wm, v, lp)
          }
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (!isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
            v.performClick()
            openCallScreen(context)
          } else {
            val screen = context.resources.displayMetrics
            val maxX = screen.widthPixels - width - margin
            val maxY = screen.heightPixels - height - margin
            val targetX = if (lp.x + width / 2 < screen.widthPixels / 2) margin else maxX
            val targetY = lp.y.coerceIn(margin, maxY)
            val fromX = lp.x
            val fromY = lp.y
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
              duration = 180
              addUpdateListener { a ->
                val t = a.animatedValue as Float
                lp.x = (fromX + (targetX - fromX) * t).roundToInt()
                lp.y = (fromY + (targetY - fromY) * t).roundToInt()
                safeUpdate(wm, v, lp)
              }
              start()
            }
          }
          true
        }
        else -> false
      }
    }
  }

  private fun safeUpdate(wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
    try {
      wm.updateViewLayout(view, lp)
    } catch (e: Exception) {
      // window went away mid-gesture
    }
  }

  /** Brings the activity forward (allowed from the background thanks to the
   * overlay permission) and asks the app to navigate to the call screen. */
  private fun openCallScreen(context: Context) {
    context.startActivity(CallsConfig.launchIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    CallActionBus.send(CallAction.OPEN)
  }

  /** One party: live video when the camera is on, initial-letter avatar otherwise. */
  private class Tile(context: Context, egl: EglBase.Context?) {
    val view = FrameLayout(context).apply { setBackgroundColor(TILE_COLOR) }
    private val renderer = TextureVideoRenderer(context)
    private val binding = VideoTrackBinding(renderer, egl)
    private val avatar = TextView(context).apply {
      setTextColor(Color.WHITE)
      textSize = 18f
      gravity = Gravity.CENTER
    }
    private var avatarName: String? = null

    init {
      val density = context.resources.displayMetrics.density
      val size = (44 * density).roundToInt()
      view.addView(renderer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
      view.addView(avatar, FrameLayout.LayoutParams(size, size).also { it.gravity = Gravity.CENTER })
    }

    fun set(track: VideoTrack?, isCamOn: Boolean, name: String, mirror: Boolean) {
      val showVideo = isCamOn && track != null
      renderer.setMirror(mirror)
      binding.bind(if (showVideo) track else null)
      renderer.visibility = if (showVideo) View.VISIBLE else View.INVISIBLE
      avatar.visibility = if (showVideo) View.GONE else View.VISIBLE
      if (avatarName != name) {
        avatarName = name
        avatar.text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        avatar.background = GradientDrawable().also {
          it.shape = GradientDrawable.OVAL
          it.setColor(avatarColor(name))
        }
      }
    }

    fun release() = binding.release()

    // same hash as fragments/CallAvatar.tsx so the colours match the app
    private fun avatarColor(name: String): Int {
      var hash = 0
      for (c in name) hash = hash * 31 + c.code
      val index = if (hash == Int.MIN_VALUE) 0 else abs(hash) % AVATAR_PALETTE.size
      return AVATAR_PALETTE[index]
    }
  }
}
