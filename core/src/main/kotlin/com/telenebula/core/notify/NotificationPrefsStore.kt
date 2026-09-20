package com.telenebula.core.notify

import android.content.Context
import com.telenebula.core.CoreJson
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.QuietHours
import java.util.Calendar
import kotlinx.serialization.SerializationException

/**
 * Global notification preferences, mirrored from prefs.json into SharedPreferences
 * so they apply while the app is backgrounded. Per-contact overrides arrive with
 * each core event and win when `useGlobal` is false.
 */
object NotificationPrefsStore {
    private const val FILE = "tn_core_prefs"
    private const val KEY = "notifications"

    @Volatile private var cached: NotificationPrefs? = null

    fun set(context: Context, prefs: NotificationPrefs) {
        cached = prefs
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, CoreJson.encodeToString(NotificationPrefs.serializer(), prefs))
            .apply()
    }

    fun get(context: Context): NotificationPrefs {
        cached?.let { return it }
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = stored?.let {
            try {
                CoreJson.decodeFromString(NotificationPrefs.serializer(), it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: NotificationPrefs()
        cached = parsed
        return parsed
    }

    /** Effective message settings for one contact (overrides > global > defaults). */
    class MessageSettings(
        val enabled: Boolean,
        val showSender: Boolean,
        val preview: Boolean,
        val sound: Boolean,
        val vibrate: Boolean,
        val popup: Boolean,
        val reactions: Boolean,
        val isQuietHours: Boolean,
    )

    fun resolve(context: Context, overrides: ContactNotificationPrefs?): MessageSettings {
        val global = get(context)
        val messages = global.messages
        val own = overrides?.takeIf { !it.useGlobal }
        return MessageSettings(
            enabled = messages.enabled && (own?.messages ?: true),
            showSender = messages.showSender,
            preview = own?.preview ?: messages.preview,
            sound = own?.sound ?: messages.sound,
            vibrate = own?.vibrate ?: messages.vibrate,
            popup = own?.popup ?: messages.popup,
            reactions = own?.reactions ?: messages.reactions,
            isQuietHours = isQuietHours(global),
        )
    }

    /** Quiet hours silence message alerts (calls still ring); ranges may wrap midnight. */
    private fun isQuietHours(prefs: NotificationPrefs): Boolean {
        val q = prefs.quietHours
        if (!q.enabled) return false
        val now = Calendar.getInstance()
        return isQuietAt(q, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
    }

    /** The window is half-open, and one that ends before it starts wraps midnight. */
    fun isQuietAt(q: QuietHours, minuteOfDay: Int): Boolean {
        val from = q.fromMinuteOfDay
        val to = q.toMinuteOfDay
        return if (from <= to) minuteOfDay >= from && minuteOfDay < to else minuteOfDay >= from || minuteOfDay < to
    }
}
