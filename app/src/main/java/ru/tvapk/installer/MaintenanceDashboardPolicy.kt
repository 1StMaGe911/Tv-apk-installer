package ru.tvapk.installer

/**
 * Keeps the one-tap maintenance entry point closed while a previous cleanup
 * operation needs reconciliation. This is a UI guard; execution still repeats
 * the durable-journal checks before any device mutation.
 */
internal object MaintenanceDashboardPolicy {
    internal fun canStartMaintenance(
        busy: Boolean,
        cleanupJournalCorrupt: Boolean,
        hasPendingCleanupJournal: Boolean
    ): Boolean = !busy && !cleanupJournalCorrupt && !hasPendingCleanupJournal
}
