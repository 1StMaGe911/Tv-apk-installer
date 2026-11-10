package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAutomationEngineTest {
    @Test
    fun planOnlyIncludesInstalledApprovedAndUnprotectedPackages() {
        val plan = TvAutomationEngine.buildPlan(
            installedPackages = setOf("safe", "review", "blocked", "never", "disabled"),
            disabledPackages = setOf("disabled"),
            detectedCandidates = setOf("safe", "review", "blocked", "never", "disabled", "missing"),
            profileApprovedPackages = setOf("safe", "blocked", "never", "disabled"),
            protectedPackages = setOf("blocked"),
            neverDisablePackages = setOf("never")
        )

        assertEquals(5, plan.detectedCount)
        assertEquals(1, plan.approvedCount)
        assertEquals(1, plan.reviewCount)
        assertEquals(2, plan.blockedCount)
        assertEquals(1, plan.alreadyDisabledCount)
        assertEquals(setOf("safe"), plan.safeToDisable)
    }

    @Test
    fun automaticCacheTrimRequiresOptInAndAtLeastNinetyPercentUsage() {
        assertFalse(TvAutomationEngine.shouldAutomaticallyTrimCache(false, 95))
        assertFalse(TvAutomationEngine.shouldAutomaticallyTrimCache(true, null))
        assertFalse(TvAutomationEngine.shouldAutomaticallyTrimCache(true, 89))
        assertTrue(TvAutomationEngine.shouldAutomaticallyTrimCache(true, 90))
        assertTrue(TvAutomationEngine.shouldAutomaticallyTrimCache(true, 95))
        assertTrue(TvAutomationEngine.shouldAutomaticallyTrimCache(true, 100))
        assertFalse(TvAutomationEngine.shouldAutomaticallyTrimCache(true, 101))
        assertFalse(TvAutomationEngine.shouldAutomaticallyTrimCache(true, -1))
    }

    @Test
    fun parsesMachineReadableDataStorageSnapshot() {
        val result = TvAutomationEngine.parseDataStorageSnapshot(
            "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-1 16777216 15728640 1048576 94% /data"
        )

        assertEquals(16777216L * 1024L, result?.totalBytes)
        assertEquals(15728640L * 1024L, result?.usedBytes)
        assertEquals(1048576L * 1024L, result?.availableBytes)
        assertEquals(94, result?.usedPercent)
    }

    @Test
    fun rejectsMalformedOrUnsafeDataStorageSnapshots() {
        assertNull(TvAutomationEngine.parseDataStorageSnapshot("not mounted"))
        assertNull(TvAutomationEngine.parseDataStorageSnapshot("/dev/block/dm-1 0 0 0 0% /data"))
        assertNull(TvAutomationEngine.parseDataStorageSnapshot("/dev/block/dm-1 100 101 0 101% /data"))
    }

    @Test
    fun rejectsStorageSnapshotsWhoseUsedAndAvailableSpaceExceedTotal() {
        assertNull(TvAutomationEngine.parseDataStorageSnapshot(
            "/dev/block/dm-1 100 70 40 70% /data"
        ))
    }

    @Test
    fun formatsFreeStorageDeltaWithCorrectDirectionAndMissingValues() {
        assertEquals("+2.0 МБ", TvAutomationEngine.formatStorageDelta(1_048_576L, 3_145_728L))
        assertEquals("−1.5 МБ", TvAutomationEngine.formatStorageDelta(3_145_728L, 1_572_864L))
        assertEquals("0 МБ", TvAutomationEngine.formatStorageDelta(2_097_152L, 2_097_152L))
        assertEquals("н/д", TvAutomationEngine.formatStorageDelta(null, 1_024L))
        assertEquals("н/д", TvAutomationEngine.formatStorageDelta(1_024L, null))
        assertEquals("н/д", TvAutomationEngine.formatStorageDelta(-1L, 1_024L))
    }

    @Test
    fun ignoresImpossibleMemoryCountersInsteadOfReportingHealthyValues() {
        val diagnostics = """
            MemTotal: 1000 kB
            MemAvailable: 1200 kB
        """.trimIndent()

        val result = TvAutomationEngine.assessHealth(diagnostics)
        assertNull(result.memoryAvailablePercent)
        assertTrue(result.summary.contains("Недостаточно данных"))
    }

    @Test
    fun healthAssessmentReportsMemoryStorageAndUptime() {
        val diagnostics = """
            ОПЕРАТИВНАЯ ПАМЯТЬ
            MemTotal:       2048000 kB
            MemFree:         200000 kB
            MemAvailable:    400000 kB

            ХРАНИЛИЩЕ /DATA
            Filesystem      Size  Used Avail Use% Mounted on
            /dev/block/dm-1  16G   15G  1G   94% /data

            ВРЕМЯ РАБОТЫ
            2592000 секунд
        """.trimIndent()

        val result = TvAutomationEngine.assessHealth(diagnostics)

        assertEquals(19, result.memoryAvailablePercent)
        assertEquals(94, result.dataUsedPercent)
        assertEquals(30, result.uptimeDays)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.contains("94%") })
        assertTrue(result.warnings.any { it.contains("30 дней") })
    }

    @Test
    fun criticalResourcePressureProducesTwoWarnings() {
        val diagnostics = """
            MemTotal: 1000000 kB
            MemFree: 40000 kB
            MemAvailable: 50000 kB
            /dev/block/dm-1  8G  7.7G  300M  96% /data
            ВРЕМЯ РАБОТЫ
            86400 секунд
        """.trimIndent()

        val result = TvAutomationEngine.assessHealth(diagnostics)

        assertEquals(5, result.memoryAvailablePercent)
        assertEquals(96, result.dataUsedPercent)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.contains("оперативной памяти") })
        assertTrue(result.warnings.any { it.contains("почти заполнено") })
    }


    @Test
    fun longUptimeSuggestsRebootWithoutTreatingItAsAnAutomaticAction() {
        val diagnostics = """
            ВРЕМЯ РАБОТЫ
            2592000 секунд
        """.trimIndent()

        val result = TvAutomationEngine.assessHealth(diagnostics)

        assertEquals(30, result.uptimeDays)
        assertTrue(result.warnings.single().contains("обычная перезагрузка"))
        assertTrue(result.summary.contains("требующие внимания"))
    }

    @Test
    fun uptimeBelowThirtyDaysDoesNotProduceRebootWarning() {
        val diagnostics = """
            ВРЕМЯ РАБОТЫ
            2505600 секунд
        """.trimIndent()

        val result = TvAutomationEngine.assessHealth(diagnostics)

        assertEquals(29, result.uptimeDays)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.summary.contains("Недостаточно данных"))
    }

    @Test
    fun missingDiagnosticsAreNotMisclassifiedAsHealthyOrFailed() {
        val result = TvAutomationEngine.assessHealth("Недоступно")

        assertNull(result.memoryAvailablePercent)
        assertNull(result.dataUsedPercent)
        assertNull(result.uptimeDays)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.summary.contains("Недостаточно данных"))
    }
}
