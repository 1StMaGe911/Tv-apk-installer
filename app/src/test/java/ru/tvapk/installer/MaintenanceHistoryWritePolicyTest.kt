package ru.tvapk.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceHistoryWritePolicyTest {
    @Test
    fun allowsCreatingHistoryWhenNoPreviousValueExists() {
        assertTrue(MaintenanceHistoryWritePolicy.canAppend(false, true))
        assertTrue(MaintenanceHistoryWritePolicy.canAppend(false, false))
    }

    @Test
    fun allowsAppendingOnlyWhenExistingHistoryWasParsedSuccessfully() {
        assertTrue(MaintenanceHistoryWritePolicy.canAppend(true, true))
        assertFalse(MaintenanceHistoryWritePolicy.canAppend(true, false))
    }

    @Test
    fun detectsMalformedHistoryWithoutTreatingMissingValueAsCorrupt() {
        assertFalse(MaintenanceHistoryWritePolicy.isCorrupt(null))
        assertFalse(MaintenanceHistoryWritePolicy.isCorrupt("[]"))
        assertTrue(MaintenanceHistoryWritePolicy.isCorrupt("{broken"))
    }
}
