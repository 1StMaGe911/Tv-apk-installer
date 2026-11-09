package ru.tvapk.installer

import org.junit.Assert.assertEquals
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
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("94%"))
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
    fun missingDiagnosticsAreNotMisclassifiedAsHealthyOrFailed() {
        val result = TvAutomationEngine.assessHealth("Недоступно")

        assertEquals(null, result.memoryAvailablePercent)
        assertEquals(null, result.dataUsedPercent)
        assertEquals(null, result.uptimeDays)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.summary.contains("Недостаточно данных"))
    }
}
