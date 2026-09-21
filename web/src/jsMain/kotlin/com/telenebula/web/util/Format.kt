package com.telenebula.web.util

import kotlin.js.Date
import kotlin.math.roundToInt

object Format {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val weekdays = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    fun clock(ts: Long): String {
        val d = Date(ts.toDouble())
        return "${two(d.getHours())}:${two(d.getMinutes())}"
    }

    private fun startOfDay(ts: Double): Double {
        val d = Date(ts)
        return Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
    }

    fun isSameDay(a: Long, b: Long): Boolean = startOfDay(a.toDouble()) == startOfDay(b.toDouble())

    fun listTime(ts: Long, now: Long = Date.now().toLong()): String {
        val today = startOfDay(now.toDouble())
        val day = startOfDay(ts.toDouble())
        val d = Date(ts.toDouble())
        return when {
            day == today -> clock(ts)
            today - day <= 86_400_000.0 -> "Yesterday"
            today - day < 6 * 86_400_000.0 -> weekdays[d.getDay()].take(3)
            d.getFullYear() == Date(now.toDouble()).getFullYear() -> "${d.getDate()} ${months[d.getMonth()]}"
            else -> "${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}"
        }
    }

    fun dayLabel(ts: Long, now: Long = Date.now().toLong()): String {
        val today = startOfDay(now.toDouble())
        val day = startOfDay(ts.toDouble())
        val d = Date(ts.toDouble())
        return when {
            day == today -> "Today"
            today - day <= 86_400_000.0 -> "Yesterday"
            else -> "${weekdays[d.getDay()]}, ${d.getDate()} ${months[d.getMonth()]}${if (d.getFullYear() != Date(now.toDouble()).getFullYear()) " ${d.getFullYear()}" else ""}"
        }
    }

    fun lastSeen(ts: Long?, now: Long = Date.now().toLong()): String {
        if (ts == null) return "Offline"
        val ago = now - ts
        return when {
            ago < 60_000 -> "Last seen just now"
            ago < 3_600_000 -> "Last seen ${ago / 60_000} min ago"
            isSameDay(ts, now) -> "Last seen at ${clock(ts)}"
            else -> "Last seen ${listTime(ts, now).lowercase()} at ${clock(ts)}"
        }
    }

    fun bytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 102.4).roundToInt() / 10.0} KB"
        bytes < 1024L * 1024 * 1024 -> "${(bytes / (1024 * 102.4)).roundToInt() / 10.0} MB"
        else -> "${(bytes / (1024 * 1024 * 102.4)).roundToInt() / 10.0} GB"
    }

    /** m:ss for clips, h:mm:ss past an hour. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${two(m.toInt())}:${two(s.toInt())}" else "${two(m.toInt())}:${two(s.toInt())}"
    }

    fun remaining(untilTs: Long, now: Long = Date.now().toLong()): String {
        val left = (untilTs - now).coerceAtLeast(0)
        return when {
            left < 60_000 -> "${left / 1000}s"
            left < 3_600_000 -> "${left / 60_000}m"
            left < 86_400_000 -> "${left / 3_600_000}h"
            else -> "${left / 86_400_000}d"
        }
    }
}
