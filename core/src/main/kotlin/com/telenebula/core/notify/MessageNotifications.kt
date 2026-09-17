package com.telenebula.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.telenebula.core.CoreNotificationConfig
import com.telenebula.core.model.ContactNotificationPrefs

/**
 * One conversation-style notification per peer. Every unread message since
 * the chat was last read stays in the expanded view; an inline Reply sends
 * through the core (and marks the chat read, which reports it as seen).
 */
object MessageNotifications {
  const val ACTION_REPLY = "com.telenebula.core.REPLY"
  const val EXTRA_IP = "ip"
  const val KEY_REPLY = "reply"
  private const val MAX_PER_PEER = 25

  /** Peers whose thread history is kept; Android itself shows far fewer notifications than this. */
  private const val MAX_THREADS = 32

  private data class Line(val text: String, val ts: Long)

  private class NotificationThread(var name: String, val lines: ArrayDeque<Line> = ArrayDeque())

  // access-ordered, so the peer heard from longest ago is the one whose history is dropped
  private val threads = object : LinkedHashMap<String, NotificationThread>(MAX_THREADS, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NotificationThread>): Boolean = size > MAX_THREADS
  }

  fun post(
    context: Context,
    ip: String,
    name: String,
    preview: String,
    isMuted: Boolean,
    overrides: ContactNotificationPrefs?,
  ) {
    val settings = NotificationPrefsStore.resolve(context, overrides)
    if (!settings.enabled || isMuted) return
    append(context, ip, name, if (settings.preview) preview else "New message", settings)
  }

  fun postReaction(
    context: Context,
    ip: String,
    name: String,
    emoji: String,
    preview: String,
    isMuted: Boolean,
    overrides: ContactNotificationPrefs?,
  ) {
    val settings = NotificationPrefsStore.resolve(context, overrides)
    if (!settings.enabled || !settings.reactions || isMuted) return
    val text = if (settings.preview) "Reacted $emoji to \"$preview\"" else "Reacted $emoji to your message"
    append(context, ip, name, text, settings)
  }

  private fun append(
    context: Context,
    ip: String,
    name: String,
    text: String,
    settings: NotificationPrefsStore.MessageSettings,
  ) {
    val shownName = if (settings.showSender) name else "TeleNebula"
    val snapshot: NotificationThread = synchronized(threads) {
      val t = threads.getOrPut(ip) { NotificationThread(shownName) }
      t.name = shownName
      t.lines.addLast(Line(text, System.currentTimeMillis()))
      while (t.lines.size > MAX_PER_PEER) t.lines.removeFirst()
      NotificationThread(t.name, ArrayDeque(t.lines))
    }
    manager(context).notify(ip.hashCode(), build(context, ip, snapshot, settings))
  }

  /** The chat was read (in the app or via an inline reply): the thread is done. */
  fun clear(context: Context, ip: String) {
    synchronized(threads) { threads.remove(ip) }
    manager(context).cancel(ip.hashCode())
  }

  private fun manager(context: Context) =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  /**
   * Android fixes sound/vibration/importance per channel once created, so each
   * combination the user can pick is its own channel. Quiet hours use the
   * fully silent one. The OS ringer mode and Do Not Disturb still apply on top.
   */
  private fun channelFor(context: Context, s: NotificationPrefsStore.MessageSettings): String {
    val sound = s.sound && !s.isQuietHours
    val vibrate = s.vibrate && !s.isQuietHours
    val popup = s.popup && !s.isQuietHours
    val id = "messages_" + (if (popup) "popup" else "quiet") + (if (sound) "_sound" else "_nosound") + (if (vibrate) "_vibrate" else "_novibrate")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val label = buildString {
        append("Messages")
        val extras = mutableListOf<String>()
        if (!popup) extras.add("no pop-up")
        if (!sound) extras.add("silent")
        if (!vibrate) extras.add("no vibration")
        if (extras.isNotEmpty()) append(" (").append(extras.joinToString(", ")).append(")")
      }
      val channel = NotificationChannel(
        id,
        label,
        if (popup) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        if (!sound) setSound(null, null)
        enableVibration(vibrate)
      }
      manager(context).createNotificationChannel(channel)
    }
    return id
  }

  private fun build(
    context: Context,
    ip: String,
    thread: NotificationThread,
    settings: NotificationPrefsStore.MessageSettings,
  ): Notification {
    val channelId = channelFor(context, settings)
    val requestCode = ip.hashCode()

    // deep link straight into this chat
    val open = PendingIntent.getActivity(
      context,
      requestCode,
      Intent(Intent.ACTION_VIEW, Uri.parse("telenebula://chats/" + Uri.encode(ip)))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val replyInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
    val replyIntent = PendingIntent.getBroadcast(
      context,
      requestCode,
      Intent(context, MessageActionReceiver::class.java)
        .setAction(ACTION_REPLY)
        .putExtra(EXTRA_IP, ip),
      // RemoteInput fills the intent, so it must be mutable (required on 31+)
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val reply = Notification.Action.Builder(null, "Reply", replyIntent)
      .addRemoteInput(replyInput)
      .setAllowGeneratedReplies(true)
      .build()

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, channelId)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(context)
    }

    val avatar = CoreNotificationConfig.avatarBitmap(thread.name)
    val style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val peerBuilder = Person.Builder().setName(thread.name).setKey(ip)
      avatar?.let { peerBuilder.setIcon(Icon.createWithBitmap(it)) }
      val peer = peerBuilder.build()
      val me = Person.Builder().setName("You").build()
      Notification.MessagingStyle(me).also { s ->
        s.conversationTitle = thread.name
        for (line in thread.lines) s.addMessage(Notification.MessagingStyle.Message(line.text, line.ts, peer))
      }
    } else {
      @Suppress("DEPRECATION")
      Notification.MessagingStyle("You").also { s ->
        s.conversationTitle = thread.name
        for (line in thread.lines) s.addMessage(line.text, line.ts, thread.name)
      }
    }

    avatar?.let { builder.setLargeIcon(it) }
    return builder
      .setStyle(style)
      .setContentTitle(thread.name)
      .setContentText(thread.lines.lastOrNull()?.text ?: "New message")
      .setNumber(thread.lines.size)
      .setSmallIcon(CoreNotificationConfig.smallIcon(context))
      .setCategory(Notification.CATEGORY_MESSAGE)
      .setContentIntent(open)
      .addAction(reply)
      .setAutoCancel(true)
      .build()
  }
}
