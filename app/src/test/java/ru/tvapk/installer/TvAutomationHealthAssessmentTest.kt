package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TvAutomationHealthAssessmentTest {
    private fun diagnostics(
        memoryTotalKb: Long = 1000,
        memoryAvailableKb: Long = 100,
        dataUsedPercent: Int = 50,
        uptimeSeconds: String = "0"
    ): String = """
        MemTotal:       ${memoryTotalKb} kB
        MemAvailable:   ${memoryAvailableKb} kB
        /dev/block/data 100000 50000 50000 ${dataUsedPercent}% /data
        ВРЕМЯ РАБОТЫ
        ${uptimeSeconds} секунд
    """.trimIndent()

    @Test
    fun memoryWarningStartsBelowTenPercent() {
        val below = TvAutomationEngine.assessHealth(diagnostics(memoryAvailableKb = 99))
        val boundary = TvAutomationEngine.assessHealth(diagnostics(memoryAvailableKb = 100))

        assertEquals(9, below.memoryAvailablePercent)
        assertTrue(below.warnings.any { it.contains("Мало доступной оперативной памяти") })
        assertEquals(10, boundary.memoryAvailablePercent)
        assertFalse(boundary.warnings.any { it.contains("оперативной памяти") })
    }

    @Test
    fun storageWarningsUseNinetyAndNinetyFivePercentBoundaries() {
        val below = TvAutomationEngine.assessHealth(diagnostics(dataUsedPercent = 89))
        val warning = TvAutomationEngine.assessHealth(diagnostics(dataUsedPercent = 90))
        val critical = TvAutomationEngine.assessHealth(diagnostics(dataUsedPercent = 95))

        assertTrue(below.warnings.none { it.contains("хранилище") })
        assertTrue(warning.warnings.any { it.contains("Мало свободного места") })
        assertTrue(critical.warnings.any { it.contains("почти заполнено") })
        assertFalse(critical.warnings.any { it.contains("Мало свободного места") })
    }

    @Test
    fun uptimeWarningStartsAtThirtyDays() {
        val below = TvAutomationEngine.assessHealth(
            diagnostics(uptimeSeconds = (30L * 86400L - 1L).toString())
        )
        val boundary = TvAutomationEngine.assessHealth(
            diagnostics(uptimeSeconds = (30L * 86400L).toString())
        )

        assertEquals(29, below.uptimeDays)
        assertTrue(below.warnings.none { it.contains("без перезагрузки") })
        assertEquals(30, boundary.uptimeDays)
        assertTrue(boundary.warnings.any { it.contains("без перезагрузки") })
    }

    @Test
    fun missingOrInvalidMeasurementsAreNotReportedAsHealthy() {
        val result = TvAutomationEngine.assessHealth("диагностика недоступна")

        assertEquals(null, result.memoryAvailablePercent)
        assertEquals(null, result.dataUsedPercent)
        assertEquals(null, result.uptimeDays)
        assertTrue(result.warnings.isEmpty())
        assertEquals("Недостаточно данных для оценки ресурсов", result.summary)
    }

    @Test
    fun impossibleMemoryMeasurementsAreIgnored() {
        val result = TvAutomationEngine.assessHealth(
            diagnostics(memoryTotalKb = 100, memoryAvailableKb = 101)
        )

        assertEquals(null, result.memoryAvailablePercent)
        assertFalse(result.warnings.any { it.contains("оперативной памяти") })
    }
}
