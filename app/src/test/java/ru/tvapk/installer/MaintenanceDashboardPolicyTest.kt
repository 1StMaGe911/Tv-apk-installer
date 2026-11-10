package ru.tvapk.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceDashboardPolicyTest {
    @Test
    fun allowsMaintenanceOnlyWhenIdleAndNoRecoveryIsPending() {
        assertTrue(MaintenanceDashboardPolicy.canStartMaintenance(false, false, false))
    }

    @Test
    fun blocksMaintenanceWhileAnotherTaskIsRunning() {
        assertFalse(MaintenanceDashboardPolicy.canStartMaintenance(true, false, false))
    }

    @Test
    fun blocksMaintenanceWhenJournalIsCorrupt() {
        assertFalse(MaintenanceDashboardPolicy.canStartMaintenance(false, true, false))
    }

    @Test
    fun blocksMaintenanceWhenCleanupRecoveryIsPending() {
        assertFalse(MaintenanceDashboardPolicy.canStartMaintenance(false, false, true))
    }

    @Test
    fun corruptJournalRemainsBlockingWhenItCannotBeParsed() {
        assertFalse(MaintenanceDashboardPolicy.canStartMaintenance(false, true, false))
    }
}
