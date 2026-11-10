package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceProgressTrackerTest {
    @Test
    fun acceptsEveryStageInTheDeclaredOrder() {
        val tracker = MaintenanceProgressTracker()
        MaintenanceStage.values().forEach { stage ->
            assertEquals(stage.label, tracker.advance(stage))
        }
        assertTrue(tracker.isComplete())
    }

    @Test
    fun rejectsSkippedStages() {
        val tracker = MaintenanceProgressTracker()
        tracker.advance(MaintenanceStage.CONNECTING)
        var rejected = false
        try {
            tracker.advance(MaintenanceStage.DIAGNOSTICS)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(tracker.isComplete())
    }

    @Test
    fun rejectsRepeatedStages() {
        val tracker = MaintenanceProgressTracker()
        tracker.advance(MaintenanceStage.CONNECTING)
        var rejected = false
        try {
            tracker.advance(MaintenanceStage.CONNECTING)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun completionIsOnlyReportedAfterFinalStage() {
        val tracker = MaintenanceProgressTracker()
        MaintenanceStage.values().dropLast(1).forEach(tracker::advance)
        assertFalse(tracker.isComplete())
        tracker.advance(MaintenanceStage.COMPLETE)
        assertTrue(tracker.isComplete())
    }
}
