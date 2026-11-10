package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallHistoryTest {
    @Test
    fun legacyWifiEntryIsMigratedAsHistoricalSuccess() {
        val entry = ApkInstallHistory.fromLegacyLine("Player.apk — 192.168.1.10:5555")
        assertEquals("Player.apk", entry.apkName)
        assertEquals("Wi-Fi ADB", entry.transport)
        assertEquals(0L, entry.timestamp)
        assertTrue(entry.success)
    }

    @Test
    fun legacyUsbEntryKeepsUsbTransport() {
        val entry = ApkInstallHistory.fromLegacyLine("Player.apk — USB Android TV")
        assertEquals("USB ADB", entry.transport)
        assertTrue(entry.details.contains("Player.apk"))
    }

    @Test
    fun failedInstallIsRenderedAsFailure() {
        val entry = ApkInstallHistoryEntry(1000L, "Player.apk", "Wi-Fi ADB", false, "ADB отклонил установку")
        val display = entry.displayText { "10.11.2026 12:00" }
        assertTrue(display.startsWith("ОШИБКА"))
        assertTrue(display.contains("ADB отклонил установку"))
        assertFalse(display.contains("УСПЕШНО"))
    }

    @Test
    fun historyIsLimitedToThirtyNewestEntries() {
        val entries = (1L..35L).map {
            ApkInstallHistoryEntry(it, "app$it.apk", "Wi-Fi ADB", true, "")
        }
        val limited = ApkInstallHistory.limit(entries)
        assertEquals(30, limited.size)
        assertEquals("app1.apk", limited.first().apkName)
        assertEquals("app30.apk", limited.last().apkName)
    }
}
