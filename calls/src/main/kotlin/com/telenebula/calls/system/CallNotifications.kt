package com.telenebula.calls.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.telenebula.calls.CallsConfig

/**
 * Builders for the two call notifications. The incoming-call channel carries
 * the ringtone + vibration, so ANDROID applies ringer mode (silent/vibrate/
 * sound) and Do Not Disturb — the app never plays the ringtone itself.
 */
object CallNotifications {
  const val INCOMING_CHANNEL_ID = "incoming_calls"
  const val INCOMING_NOVIBRATE_CHANNEL_ID = "incoming_calls_novibrate"
  const val ONGOING_CHANNEL_ID = "ongoing_call"
  const val MISSED_CHANNEL_ID = "missed_calls"
  const val INCOMING_ID = 4301
  const val ONGOING_ID = 4302

  const val ACTION_ANSWER = "com.telenebula.calls.ANSWER"
  const val ACTION_DECLINE = "com.telenebula.calls.DECLINE"
  const val ACTION_HANGUP = "com.telenebula.calls.HANGUP"

  // distinct request codes: extras do not distinguish PendingIntents, so one code would make Answer and Open the same intent
  private const val OPEN_REQUEST = 0
  private const val ANSWER_REQUEST = 1

  @Volatile private var areChannelsReady = false

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || areChannelsReady) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val ringtone = Uri.parse("android.resource://${context.packageName}/raw/call_ringtone")
    val incoming = NotificationChannel(
      INCOMING_CHANNEL_ID,
      "Incoming calls",
      NotificationManager.IMPORTANCE_HIGH
    ).apply {
      setSound(
        ringtone,
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      )
      enableVibration(true)
      vibrationPattern = longArrayOf(0, 900, 700)
      setBypassDnd(false)
    }
    manager.createNotificationChannel(incoming)

    // same ringtone, no vibration — chosen by the "Vibrate while ringing" setting
    manager.createNotificationChannel(
      NotificationChannel(
        INCOMING_NOVIBRATE_CHANNEL_ID,
        "Incoming calls (no vibration)",
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        setSound(
          ringtone,
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        enableVibration(false)
        setBypassDnd(false)
      }
    )

    manager.createNotificationChannel(
      NotificationChannel(MISSED_CHANNEL_ID, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT)
    )

    manager.createNotificationChannel(
      NotificationChannel(
        ONGOING_CHANNEL_ID,
        "Ongoing call",
        NotificationManager.IMPORTANCE_LOW
      ).apply { setShowBadge(false) }
    )
    areChannelsReady = true
  }

  private fun actionIntent(context: Context, action: String): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      action.hashCode(),
      Intent(context, CallActionReceiver::class.java).setAction(action),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

  private fun launchIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      OPEN_REQUEST,
      CallsConfig.launchIntent(context),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

  // Android 12 drops an activity start made from a notification's broadcast receiver, so Answer is an activity intent
  private fun answerIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      ANSWER_REQUEST,
      CallsConfig.launchIntent(context).putExtra(CallsConfig.EXTRA_ANSWER_CALL, true),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

  fun showIncoming(context: Context, name: String, isVideo: Boolean, vibrate: Boolean = true) {
    ensureChannels(context)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val title = if (isVideo) "Incoming video call" else "Incoming call"
    val answer = answerIntent(context)
    val decline = actionIntent(context, ACTION_DECLINE)
    val channelId = if (vibrate) INCOMING_CHANNEL_ID else INCOMING_NOVIBRATE_CHANNEL_ID
    val avatar = CallsConfig.avatarBitmap(name)

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val personBuilder = Person.Builder().setName(name).setImportant(true)
      avatar?.let { personBuilder.setIcon(Icon.createWithBitmap(it)) }
      val person = personBuilder.build()
      Notification.Builder(context, channelId)
        .setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
        .addPerson(person)
        .setContentText(title)
    } else {
      val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, channelId)
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
      }
      base
        .setContentTitle(name)
        .setContentText(title)
        .addAction(Notification.Action.Builder(null, "Decline", decline).build())
        .addAction(Notification.Action.Builder(null, "Answer", answer).build())
    }

    avatar?.let { builder.setLargeIcon(it) }
    val notification = builder
      .setSmallIcon(CallsConfig.smallIcon)
      .setCategory(Notification.CATEGORY_CALL)
      .setFullScreenIntent(launchIntent(context), true)
      .setContentIntent(launchIntent(context))
      .setOngoing(true)
      .setOnlyAlertOnce(false)
      .build()
    // INSISTENT loops the channel's ringtone/vibration until cancelled
    notification.flags = notification.flags or Notification.FLAG_INSISTENT

    manager.notify(INCOMING_ID, notification)
  }

  fun hideIncoming(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.cancel(INCOMING_ID)
  }

  /** Plain "Missed call" entry (one per caller); tap opens the app. */
  fun showMissed(context: Context, name: String, isVideo: Boolean) {
    ensureChannels(context)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, MISSED_CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(context)
    }
    CallsConfig.avatarBitmap(name)?.let { builder.setLargeIcon(it) }
    manager.notify(
      ("missed:$name").hashCode(),
      builder
        .setContentTitle(if (isVideo) "Missed video call" else "Missed call")
        .setContentText(name)
        .setSmallIcon(CallsConfig.smallIcon)
        .setCategory(Notification.CATEGORY_MISSED_CALL)
        .setContentIntent(launchIntent(context))
        .setAutoCancel(true)
        .build()
    )
  }

  /**
   * connectedAtMs anchors the live chronometer at the moment the call was
   * ANSWERED; 0 means not connected yet (dialing/ringing) — no timer shown,
   * so the caller's clock never counts ring time.
   */
  fun buildOngoing(
    context: Context,
    name: String,
    isVideo: Boolean,
    connectedAtMs: Long,
  ): Notification {
    ensureChannels(context)
    val hangUp = actionIntent(context, ACTION_HANGUP)
    val isConnected = connectedAtMs > 0
    val label = when {
      !isConnected -> "Calling…"
      isVideo -> "Video call"
      else -> "Call"
    }

    val avatar = CallsConfig.avatarBitmap(name)
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val personBuilder = Person.Builder().setName(name).setImportant(true)
      avatar?.let { personBuilder.setIcon(Icon.createWithBitmap(it)) }
      val person = personBuilder.build()
      Notification.Builder(context, ONGOING_CHANNEL_ID)
        .setStyle(Notification.CallStyle.forOngoingCall(person, hangUp))
        .addPerson(person)
        .setContentText(label)
    } else {
      val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, ONGOING_CHANNEL_ID)
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
      }
      base
        .setContentTitle(name)
        .setContentText(label)
        .addAction(Notification.Action.Builder(null, "Hang up", hangUp).build())
    }

    if (isConnected) {
      builder.setWhen(connectedAtMs).setUsesChronometer(true)
    }
    avatar?.let { builder.setLargeIcon(it) }
    return builder
      .setSmallIcon(CallsConfig.smallIcon)
      .setCategory(Notification.CATEGORY_CALL)
      .setContentIntent(launchIntent(context))
      .setOngoing(true)
      .build()
  }
}
