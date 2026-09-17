package com.telenebula.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** System intents: share, open-with, per-app notification settings, external URLs. */
class OpenWith(context: Context) {
    private val app = context.applicationContext
    private val authority = "${app.packageName}.files"

    fun contentUri(file: File): Uri = FileProvider.getUriForFile(app, authority, file)

    /** System share sheet for a local file (diagnostics export, chat export). */
    fun shareFile(file: File, mime: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, contentUri(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(Intent.createChooser(send, null))
    }

    /** Always the system "Open with" chooser, never a silent default app. */
    fun openFile(file: File, mime: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri(file), mime.ifEmpty { mimeOf(file) })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(Intent.createChooser(view, "Open with"))
    }

    private fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    /** Android's per-app notification settings (channels, tones, DND exceptions). */
    fun openAppNotificationSettings() {
        launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName),
        )
    }

    fun openUrl(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            launch(view)
        } catch (e: ActivityNotFoundException) {
            launch(Intent.createChooser(view, null))
        }
    }

    private fun launch(intent: Intent) {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
