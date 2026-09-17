package com.telenebula.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.telenebula.app.MainActivity
import com.telenebula.app.R

/** One notification per newer release; tapping it lands on the Updates screen. */
class UpdateNotifier(context: Context) {
    private val app = context.applicationContext
    private val manager: NotificationManager? = app.getSystemService(NotificationManager::class.java)

    fun show(version: String) {
        val manager = manager ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_UPDATES, true)
        val tap = PendingIntent.getActivity(app, REQUEST_CODE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("TeleNebula $version is available")
            .setContentText("Tap to download and install the update.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun clear() {
        manager?.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val EXTRA_OPEN_UPDATES = "com.telenebula.app.OPEN_UPDATES"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 7001
        private const val REQUEST_CODE = 7001
    }
}
