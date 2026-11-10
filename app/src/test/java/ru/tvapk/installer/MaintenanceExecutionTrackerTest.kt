package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceExecutionTrackerTest {
    @Test
    fun acceptsTheFullExecutionSequence() {
        val tracker = MaintenanceExecutionTracker()
        MaintenanceExecutionStage.values().take(4).forEach { stage ->
            assertEquals(stage.label, tracker.advance(stage))
        }
        assertFalse(tracker.isComplete())
        assertEquals(MaintenanceExecutionStage.COMPLETE.label, tracker.finish(historySaved = true))
        assertTrue(tracker.isComplete())
    }

    @Test
    fun reportsWarningWhenHistoryCouldNotBeSaved() {
        val tracker = MaintenanceExecutionTracker()
        MaintenanceExecutionStage.values().take(4).forEach(tracker::advance)

        assertEquals(
            MaintenanceExecutionStage.COMPLETE_WITH_WARNING.label,
            tracker.finish(historySaved = false)
        )
        assertTrue(tracker.isComplete())
    }

    @Test
    fun rejectsSkippedOrRepeatedExecutionStages() {
        val skipped = MaintenanceExecutionTracker()
        skipped.advance(MaintenanceExecutionStage.PREPARING)
        assertTrue(runCatching {
            skipped.advance(MaintenanceExecutionStage.VERIFYING)
        }.isFailure)

        val repeated = MaintenanceExecutionTracker()
        repeated.advance(MaintenanceExecutionStage.PREPARING)
        assertTrue(runCatching {
            repeated.advance(MaintenanceExecutionStage.PREPARING)
        }.isFailure)
    }

    @Test
    fun rejectsCompletionBeforeReportStage() {
        val tracker = MaintenanceExecutionTracker()
        tracker.advance(MaintenanceExecutionStage.PREPARING)
        assertTrue(runCatching { tracker.finish(historySaved = true) }.isFailure)
        assertFalse(tracker.isComplete())
    }
}
