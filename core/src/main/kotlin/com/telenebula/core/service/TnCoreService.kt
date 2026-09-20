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
import com.telenebula.core.CoreLog
import com.telenebula.core.CoreNotificationConfig
import com.telenebula.core.notify.MessageActionReceiver

/**
 * Foreground service that keeps the app process (and with it the messaging
 * engine and the nebula tunnel) alive while the activity is backgrounded, so
 * messages keep arriving. Its notification shows the tunnel state and offers
 * a Disconnect action; turning the tunnel off from there also stops this
 * service (the app does both).
 *
 * Nothing here treats the foreground state as owed: the system may refuse it, and every refusal
 * used to be a crash. The service asks, and stops quietly when the answer is no.
 */
class TnCoreService : Service() {
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isRunning = true
    if (goForeground()) return START_STICKY
    // The allowance for this type is spent, or we were started from the background with no
    // exemption. Stopping is the honest answer: the app asks again the next time it is opened,
    // and the alternative the platform offers is killing the process.
    isRunning = false
    stopSelf()
    return START_NOT_STICKY
  }

  /**
   * API 35+ calls this when a time-limited type runs out, and kills the app if it does not stop
   * within seconds. The type used from API 34 has no limit, so this is the answer for a platform
   * that limits something later, and for the older type below it.
   */
  override fun onTimeout(startId: Int) = stopForTimeout()

  override fun onTimeout(startId: Int, fgsType: Int) = stopForTimeout()

  override fun onDestroy() {
    isRunning = false
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun stopForTimeout() {
    CoreLog.warn(TAG, "the system timed out the background service; stopping before it kills us")
    isRunning = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  /** False when the system refused the foreground state, which it may always do. */
  private fun goForeground(): Boolean = try {
    val notification = notification(this)
    when {
      // dataSync is capped at six hours a day from API 35; specialUse carries no limit
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
        startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      else -> startForeground(ONGOING_ID, notification)
    }
    true
  } catch (e: IllegalStateException) {
    // ForegroundServiceStartNotAllowedException is one of these; naming it would not resolve on
    // the releases that have no such class
    CoreLog.warn(TAG, "the system refused the foreground service: ${e.message}")
    false
  }

  companion object {
    private const val TAG = "TnCoreService"
    const val CHANNEL_ID = "tn_core_background"
    const val ONGOING_ID = 4201
    const val ACTION_TOGGLE_TUNNEL = "com.telenebula.core.TOGGLE_TUNNEL"

    @Volatile var isRunning = false
    @Volatile var isTunnelRunning = false

    fun start(context: Context) {
      val intent = Intent(context, TnCoreService::class.java)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: IllegalStateException) {
        CoreLog.warn(TAG, "the system refused to start the background service: ${e.message}")
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, TnCoreService::class.java))
    }

    /** Re-posts the notification with the current tunnel state (no-op when not running). */
    fun setTunnelState(context: Context, running: Boolean) {
      isTunnelRunning = running
      if (!isRunning) return
      // notify() rather than start(): every start is a foreground request the system may refuse,
      // and a change of wording is not worth asking for the service all over again
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      runCatching { manager.notify(ONGOING_ID, notification(context)) }
    }

    private fun notification(context: Context): Notification {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(
          NotificationChannel(
            CHANNEL_ID,
            "Background connection",
            NotificationManager.IMPORTANCE_MIN
          ).apply { setShowBadge(false) }
        )
      }
      val tap = PendingIntent.getActivity(
        context,
        0,
        CoreNotificationConfig.launchIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
      val toggle = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, MessageActionReceiver::class.java).setAction(ACTION_TOGGLE_TUNNEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
      val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, CHANNEL_ID)
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
      }
      val text = if (isTunnelRunning) "Nebula tunnel connected" else "Nebula tunnel off"
      val action = if (isTunnelRunning) "Disconnect tunnel" else "Connect tunnel"
      return builder
        .setContentTitle("TeleNebula")
        .setContentText(text)
        .setSmallIcon(CoreNotificationConfig.smallIcon(context))
        .setContentIntent(tap)
        .addAction(Notification.Action.Builder(null, action, toggle).build())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
    }
  }
}
