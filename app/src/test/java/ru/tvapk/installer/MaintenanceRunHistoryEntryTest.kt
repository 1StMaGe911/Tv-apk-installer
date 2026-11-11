package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceRunHistoryEntryTest {
    private fun entry() = MaintenanceRunHistoryEntry(
        createdAt = 123L,
        deviceIdentity = "test-device",
        memoryBefore = 40,
        memoryAfter = 55,
        storageBefore = 80,
        storageAfter = 65,
        cacheTrimAttempted = false,
        cacheTrimOutcome = "Выключено пользователем",
        detectedCandidates = 4,
        approvedCandidates = 2,
        reviewCandidates = 1,
        blockedCandidates = 1,
        warnings = listOf("warning"),
        cleanupOutcome = "Очистка завершена",
        changedPackages = listOf("com.example.test")
    )

    @Test
    fun launcherInstallOutcomeDefaultsForExistingHistoryEntries() {
        assertEquals("Не запускалась", entry().launcherInstallOutcome)
    }

    @Test
    fun launcherInstallResultUpdatesOnlyInstallationOutcome() {
        val original = entry()
        val updated = original.withLauncherInstallResult("Успешно установлены и проверены Launcher и Curtain")

        assertEquals("Успешно установлены и проверены Launcher и Curtain", updated.launcherInstallOutcome)
        assertEquals(original.cleanupOutcome, updated.cleanupOutcome)
        assertEquals(original.changedPackages, updated.changedPackages)
        assertEquals(original.deviceIdentity, updated.deviceIdentity)
        assertTrue(updated.warnings.contains("warning"))
    }
}
