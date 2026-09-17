package com.telenebula.app.platform

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The strings the RN app showed, byte for byte. */
class FormatTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `list time is a clock today, a month and day this year, a short date otherwise`() {
        val now = at(2026, 9, 16, 14, 43)
        assertEquals("09:05", Format.listTime(at(2026, 9, 16, 9, 5), now))
        assertEquals("Aug 22", Format.listTime(at(2026, 8, 22, 9, 5), now))
        assertEquals("22.08.24", Format.listTime(at(2024, 8, 22, 9, 5), now))
    }

    @Test
    fun `the date pill adds the year only when it is not the current one`() {
        val now = at(2026, 9, 16, 12, 0)
        assertEquals("July 22", Format.datePill(at(2026, 7, 22, 12, 0), now))
        assertEquals("July 22, 2025", Format.datePill(at(2025, 7, 22, 12, 0), now))
    }

    @Test
    fun `last seen degrades with distance`() {
        val now = at(2026, 9, 16, 12, 0)
        assertEquals("last seen a long time ago", Format.lastSeen(null, now))
        assertEquals("online", Format.lastSeen(now - 30_000, now))
        assertEquals("last seen just now", Format.lastSeen(now - 4 * 60_000, now))
        assertEquals("last seen recently", Format.lastSeen(now - 30 * 60_000, now))
        assertEquals("last seen at 08:00", Format.lastSeen(at(2026, 9, 16, 8, 0), now))
        assertEquals("last seen Sep 1", Format.lastSeen(at(2026, 9, 1, 8, 0), now))
        assertEquals("last seen within a year", Format.lastSeen(at(2025, 9, 1, 8, 0), now))
    }

    @Test
    fun `durations, uptimes, quantities and byte sizes`() {
        assertEquals("00:00", Format.duration(null))
        assertEquals("01:05", Format.duration(1_000, 66_000))
        assertEquals("1:01:05", Format.duration(1_000, 3_666_000))
        assertEquals("0:07", Format.durationBetween(0, 7_500))
        assertEquals("1 week", Format.seconds(604_800))
        assertEquals("2 days", Format.seconds(172_800))
        assertEquals("1 hour", Format.seconds(3_600))
        assertEquals("5 minutes", Format.seconds(300))
        assertEquals("45 seconds", Format.seconds(45))
        assertEquals("3d 4h", Format.uptime((3 * 86_400 + 4 * 3_600) * 1000L))
        assertEquals("2h 05m", Format.uptime((2 * 3_600 + 5 * 60) * 1000L))
        assertEquals("12m", Format.uptime(12 * 60_000L))
        assertEquals("45s", Format.uptime(45_000))
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("2 KB", Format.bytes(2_048))
        assertEquals("1.5 MB", Format.bytes(1_572_864))
    }
}
