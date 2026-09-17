package com.telenebula.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.telenebula.core.CoreNotificationConfig
import com.telenebula.core.notify.MessageActionReceiver

/**
 * Foreground service that keeps the app process (and with it the messaging
 * engine and the nebula tunnel) alive while the activity is backgrounded, so
 * messages keep arriving. Its notification shows the tunnel state and offers
 * a Disconnect action; turning the tunnel off from there also stops this
 * service (the app does both).
 */
class TnCoreService : Service() {
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isRunning = true
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(ONGOING_ID, notification)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    isRunning = false
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun buildNotification(): Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          "Background connection",
          NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
      )
    }
    val launch = CoreNotificationConfig.launchIntent(this)
    val tap = PendingIntent.getActivity(
      this,
      0,
      launch,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val toggle = PendingIntent.getBroadcast(
      this,
      1,
      Intent(this, MessageActionReceiver::class.java).setAction(ACTION_TOGGLE_TUNNEL),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    val text = if (isTunnelRunning) "Nebula tunnel connected" else "Nebula tunnel off"
    val action = if (isTunnelRunning) "Disconnect tunnel" else "Connect tunnel"
    return builder
      .setContentTitle("TeleNebula")
      .setContentText(text)
      .setSmallIcon(CoreNotificationConfig.smallIcon(this))
      .setContentIntent(tap)
      .addAction(Notification.Action.Builder(null, action, toggle).build())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "tn_core_background"
    const val ONGOING_ID = 4201
    const val ACTION_TOGGLE_TUNNEL = "com.telenebula.core.TOGGLE_TUNNEL"

    @Volatile var isRunning = false
    @Volatile var isTunnelRunning = false

    fun start(context: Context) {
      val intent = Intent(context, TnCoreService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, TnCoreService::class.java))
    }

    /** Re-posts the notification with the current tunnel state (no-op when not running). */
    fun setTunnelState(context: Context, running: Boolean) {
      isTunnelRunning = running
      if (isRunning) start(context)
    }
  }
}
