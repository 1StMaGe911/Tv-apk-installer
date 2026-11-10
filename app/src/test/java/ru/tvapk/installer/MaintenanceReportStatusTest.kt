package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceReportStatusTest {
    @Test
    fun reportsSuccessfulCompletionWhenHistoryWasSaved() {
        assertEquals(
            "АВТОМАТИЧЕСКАЯ ПРОВЕРКА ЗАВЕРШЕНА",
            MaintenanceReportStatus.title(historySaved = true)
        )
    }

    @Test
    fun reportsWarningWhenHistoryCouldNotBeSaved() {
        assertEquals(
            "АВТОМАТИЧЕСКАЯ ПРОВЕРКА ЗАВЕРШЕНА С ПРЕДУПРЕЖДЕНИЕМ",
            MaintenanceReportStatus.title(historySaved = false)
        )
    }
}
