package com.telenebula.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.telenebula.app.ui.theme.ThemeResolver

/**
 * Renders the same initials-on-colour circle as the in-app `Avatar` composable, as a plain
 * [Bitmap] for system notifications (large icon / `Person` icon) — there are no profile photos,
 * only nicknames/names, so this is the only avatar TeleNebula ever has to show.
 */
object AvatarBitmaps {
    private const val SIZE_PX = 128

    fun render(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = SIZE_PX / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeResolver.avatarColor(name).toArgb() }
        canvas.drawCircle(radius, radius, radius, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE_PX * 0.42f
            textAlign = Paint.Align.CENTER
        }
        val metrics = text.fontMetrics
        canvas.drawText(ThemeResolver.initialOf(name), radius, radius - (metrics.ascent + metrics.descent) / 2f, text)
        return bitmap
    }
}
