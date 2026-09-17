package com.telenebula.calls.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service for the DURATION of a call. Its phoneCall/microphone/
 * camera service types are what tell Android the user is on a call: the
 * process is exempt from background mic/camera cutoffs (Android 11+/14+) and
 * treated with call priority. The CallStyle notification it owns shows the
 * live chronometer and a Hang up action.
 */
class CallSessionService : Service() {
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val name = intent?.getStringExtra(EXTRA_NAME) ?: "Call"
    val isVideo = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
    val connectedAt = intent?.getLongExtra(EXTRA_CONNECTED_AT, 0L) ?: 0L
    val notification = CallNotifications.buildOngoing(this, name, isVideo, connectedAt)

    // Android 14+ enforces per-type prerequisites (phoneCall needs
    // MANAGE_OWN_CALLS, microphone needs granted RECORD_AUDIO, camera needs
    // granted CAMERA) with a SecurityException. Try the richest combination
    // first and degrade — a throw in here would take down the whole app.
    if (!startForegroundSafely(notification, isVideo)) {
      stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun startForegroundSafely(
    notification: android.app.Notification,
    isVideo: Boolean,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return try {
        startForeground(CallNotifications.ONGOING_ID, notification)
        true
      } catch (e: Exception) {
        false
      }
    }

    val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    val mic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
      0
    }
    val cam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isVideo) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    } else {
      0
    }

    val attempts = listOf(phone or mic or cam, phone or mic, phone, mic).distinct()
    for (types in attempts) {
      if (types == 0) continue
      try {
        startForeground(CallNotifications.ONGOING_ID, notification, types)
        return true
      } catch (e: Exception) {
        // prerequisite missing for this combination — try a weaker one
      }
    }
    return try {
      // typeless as the last resort (rejected on targetSdk 34+, fine below)
      startForeground(CallNotifications.ONGOING_ID, notification)
      true
    } catch (e: Exception) {
      false
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val EXTRA_NAME = "name"
    private const val EXTRA_VIDEO = "video"
    private const val EXTRA_CONNECTED_AT = "connectedAt"

    fun start(context: Context, name: String, isVideo: Boolean, connectedAtMs: Long) {
      val intent = Intent(context, CallSessionService::class.java)
        .putExtra(EXTRA_NAME, name)
        .putExtra(EXTRA_VIDEO, isVideo)
        .putExtra(EXTRA_CONNECTED_AT, connectedAtMs)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, CallSessionService::class.java))
    }
  }
}
