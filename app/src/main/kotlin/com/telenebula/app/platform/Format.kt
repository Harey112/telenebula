package com.telenebula.app.platform

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** English, fixed-format timestamps and quantities; byte-for-byte the strings the RN app showed. */
object Format {
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTHS_FULL = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private fun local(ts: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

    private fun Int.two(): String = if (this < 10) "0$this" else toString()

    fun clock(ts: Long): String {
        val d = local(ts)
        return "${d.hour.two()}:${d.minute.two()}"
    }

    /** Chat-list style timestamp: today → 14:43, this year → Aug 22, else → 22.08.24 */
    fun listTime(ts: Long, now: Long = System.currentTimeMillis()): String {
        val d = local(ts)
        val today = local(now)
        if (d.toLocalDate() == today.toLocalDate()) return "${d.hour.two()}:${d.minute.two()}"
        if (d.year == today.year) return "${MONTHS[d.monthValue - 1]} ${d.dayOfMonth}"
        return "${d.dayOfMonth.two()}.${d.monthValue.two()}.${(d.year % 100).two()}"
    }

    /** Chat date pill: "July 22" (adds the year when not current). */
    fun datePill(ts: Long, now: Long = System.currentTimeMillis()): String {
        val d = local(ts)
        val base = "${MONTHS_FULL[d.monthValue - 1]} ${d.dayOfMonth}"
        return if (d.year == local(now).year) base else "$base, ${d.year}"
    }

    /** Calendar day of a timestamp, for grouping messages under date pills. */
    fun dayOf(ts: Long): LocalDate = local(ts).toLocalDate()

    fun lastSeen(ts: Long?, now: Long = System.currentTimeMillis()): String {
        if (ts == null || ts <= 0) return "last seen a long time ago"
        val diff = now - ts
        if (diff < 60_000) return "online"
        if (diff < 5 * 60_000) return "last seen just now"
        if (diff < 60 * 60_000) return "last seen recently"
        val d = local(ts)
        val today = local(now)
        if (d.toLocalDate() == today.toLocalDate()) return "last seen at ${clock(ts)}"
        if (d.year == today.year) return "last seen ${MONTHS[d.monthValue - 1]} ${d.dayOfMonth}"
        return "last seen within a year"
    }

    /** Running call timer: mm:ss, or h:mm:ss past an hour. */
    fun duration(startedAt: Long?, now: Long = System.currentTimeMillis()): String {
        if (startedAt == null || startedAt <= 0) return "00:00"
        val total = ((now - startedAt) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = ((total % 3600) / 60).toInt().two()
        val s = (total % 60).toInt().two()
        return if (h > 0) "$h:$m:$s" else "$m:$s"
    }

    /** Elapsed time between two timestamps as m:ss or h:mm:ss. */
    fun durationBetween(from: Long, to: Long): String {
        val total = ((to - from) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = ((total % 3600) / 60).toInt()
        val s = (total % 60).toInt().two()
        return if (h > 0) "$h:${m.two()}:$s" else "$m:$s"
    }

    /** "30 seconds", "1 minute", "1 hour", "1 day", "1 week" or "N seconds" */
    fun seconds(seconds: Int): String {
        fun unit(size: Int, name: String) = "${seconds / size} $name${if (seconds == size) "" else "s"}"
        return when {
            seconds % 604_800 == 0 -> unit(604_800, "week")
            seconds % 86_400 == 0 -> unit(86_400, "day")
            seconds % 3_600 == 0 -> unit(3_600, "hour")
            seconds % 60 == 0 -> unit(60, "minute")
            else -> "$seconds seconds"
        }
    }

    /** "3d 4h", "2h 05m", "12m", "45s" */
    fun uptime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val d = total / 86_400
        val h = (total % 86_400) / 3_600
        val m = ((total % 3_600) / 60).toInt()
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m.two()}m"
            m > 0 -> "${m}m"
            else -> "${total}s"
        }
    }

    fun bytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${Math.round(bytes / 1024.0)} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
