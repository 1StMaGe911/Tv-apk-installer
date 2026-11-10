package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAutomationEngineTest {
    @Test
    fun cleanupJournalValidatorAcceptsValidJournal() {
        assertTrue(CleanupJournalValidator.isValid(
            deviceIdentity = "Hisense/TV/build",
            createdAt = 1_700_000_000_000L,
            schemaVersion = 3,
            plannedPackages = listOf("com.example.safe", "org.example.tv"),
            completedPackages = listOf("com.example.safe"),
            inFlightPackage = "org.example.tv"
        ))
    }

    @Test
    fun cleanupJournalValidatorRejectsMissingIdentityOrTimestamp() {
        assertFalse(CleanupJournalValidator.isValid("", 1L, 3, listOf("com.example.app"), emptyList()))
        assertFalse(CleanupJournalValidator.isValid("tv", 0L, 3, listOf("com.example.app"), emptyList()))
    }

    @Test
    fun cleanupJournalValidatorRejectsUnknownSchemaAndEmptyPlan() {
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 4, listOf("com.example.app"), emptyList()))
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 3, emptyList(), emptyList()))
    }

    @Test
    fun cleanupJournalValidatorRejectsInvalidOrDuplicatePackageNames() {
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 3, listOf("not a package"), emptyList()))
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 3, listOf("com.example.app", "com.example.app"), emptyList()))
    }

    @Test
    fun cleanupJournalValidatorRejectsCompletedOrInFlightPackagesOutsidePlan() {
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 3, listOf("com.example.app"), listOf("org.example.other")))
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 3, listOf("com.example.app"), emptyList(), "org.example.other"))
    }

    @Test
    fun cleanupJournalValidatorRejectsDuplicateCompletedPackages() {
        assertFalse(CleanupJournalValidator.isValid("tv", 1L, 3, listOf("com.example.app"), listOf("com.example.app", "com.example.app")))
    }
    @Test
    fun cleanupJournalValidatorRejectsInvalidBaselineDisabledPackages() {
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            baselineDisabledPackages = listOf("not a package")
        ))
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            baselineDisabledPackages = listOf("com.example.app", "com.example.app")
        ))
    }

    @Test
    fun cleanupJournalValidatorRejectsRestoredPackagesOutsidePlanOrDuplicates() {
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            restoredPackages = listOf("org.example.other")
        ))
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            restoredPackages = listOf("com.example.app", "com.example.app")
        ))
    }

    @Test
    fun cleanupJournalValidatorRejectsBaselinePackagesIncludedInThePlan() {
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            baselineDisabledPackages = listOf("com.example.app")
        ))
    }

    @Test
    fun cleanupJournalValidatorRejectsRestoringPackagesDisabledBeforeMaintenance() {
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            baselineDisabledPackages = listOf("com.example.previouslydisabled"),
            restoredPackages = listOf("com.example.previouslydisabled")
        ))
    }

    @Test
    fun cleanupJournalValidatorAcceptsDisjointBaselineAndChangedPackages() {
        assertTrue(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.changed"), emptyList(),
            baselineDisabledPackages = listOf("com.example.previouslydisabled"),
            restoredPackages = listOf("com.example.changed")
        ))
    }

    @Test
    fun cleanupJournalValidatorRejectsUnknownCacheTrimState() {
        assertFalse(CleanupJournalValidator.isValid(
            "tv", 1L, 3, listOf("com.example.app"), emptyList(),
            cacheTrimState = "succeeded_but_unverified"
        ))
    }

    @Test
    fun cleanupJournalValidatorAcceptsLegacyJournalDefaults() {
        assertTrue(CleanupJournalValidator.isValid(
            "tv", 1L, 1, listOf("com.example.app"), emptyList()
        ))
    }

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
    fun recoveryReconcilesInFlightPackageAlreadyDisabledWithoutRepeatingIt() {
        val result = TvAutomationEngine.buildCleanupRecoveryPlan(
            plannedPackages = setOf("com.example.done", "com.example.inFlight", "com.example.pending"),
            completedPackages = setOf("com.example.done"),
            installedPackages = setOf("com.example.done", "com.example.inFlight", "com.example.pending"),
            disabledPackages = setOf("com.example.done", "com.example.inFlight"),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )
        assertEquals(setOf("com.example.done", "com.example.inFlight"), result.confirmedCompletedPackages)
        assertEquals(setOf("com.example.pending"), result.retryablePackages)
        assertTrue(result.conflictPackages.isEmpty())
    }

    @Test
    fun recoveryDoesNotRetryPreviouslyCompletedPackageThatIsNowEnabled() {
        val result = TvAutomationEngine.buildCleanupRecoveryPlan(
            plannedPackages = setOf("com.example.app"),
            completedPackages = setOf("com.example.app"),
            installedPackages = setOf("com.example.app"),
            disabledPackages = emptySet(),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )
        assertTrue(result.retryablePackages.isEmpty())
        assertEquals(setOf("com.example.app"), result.conflictPackages)
    }

    @Test
    fun recoveryReportsMissingPackageAsConflict() {
        val result = TvAutomationEngine.buildCleanupRecoveryPlan(
            plannedPackages = setOf("com.example.missing"),
            completedPackages = emptySet(),
            installedPackages = emptySet(),
            disabledPackages = emptySet(),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )
        assertEquals(setOf("com.example.missing"), result.conflictPackages)
        assertTrue(result.retryablePackages.isEmpty())
    }

    @Test
    fun recoveryExcludesBaselineAndAlreadyRestoredPackages() {
        val result = TvAutomationEngine.buildCleanupRecoveryPlan(
            plannedPackages = setOf("com.example.baseline", "com.example.restored", "com.example.pending"),
            completedPackages = emptySet(),
            installedPackages = setOf("com.example.baseline", "com.example.restored", "com.example.pending"),
            disabledPackages = setOf("com.example.baseline"),
            baselineDisabledPackages = setOf("com.example.baseline"),
            restoredPackages = setOf("com.example.restored")
        )
        assertEquals(setOf("com.example.pending"), result.retryablePackages)
        assertTrue(result.confirmedCompletedPackages.isEmpty())
        assertTrue(result.conflictPackages.isEmpty())
    }

    @Test
    fun rollbackFinalSnapshotConfirmsOnlyInstalledAndEnabledPackages() {
        val result = TvAutomationEngine.verifyRollbackState(
            rollbackPackages = setOf("restored", "still-disabled", "missing"),
            installedPackages = setOf("restored", "still-disabled"),
            disabledPackages = setOf("still-disabled")
        )

        assertEquals(setOf("restored"), result.restoredPackages)
        assertEquals(setOf("still-disabled", "missing"), result.remainingPackages)
    }

    @Test
    fun rollbackFinalSnapshotCanConfirmRecoveryAfterAnUncertainCommand() {
        val result = TvAutomationEngine.verifyRollbackState(
            rollbackPackages = setOf("enabled-after-command-error"),
            installedPackages = setOf("enabled-after-command-error"),
            disabledPackages = emptySet()
        )

        assertEquals(setOf("enabled-after-command-error"), result.restoredPackages)
        assertTrue(result.remainingPackages.isEmpty())
    }

    @Test
    fun rollbackFinalSnapshotKeepsPackagesWhenDeviceStillReportsThemDisabled() {
        val result = TvAutomationEngine.verifyRollbackState(
            rollbackPackages = setOf("not-restored"),
            installedPackages = setOf("not-restored"),
            disabledPackages = setOf("not-restored")
        )

        assertTrue(result.restoredPackages.isEmpty())
        assertEquals(setOf("not-restored"), result.remainingPackages)
    }

    @Test
    fun cleanupFinalSnapshotCountsOnlyPackagesStillInstalledAndDisabled() {
        val result = TvAutomationEngine.verifyCleanupState(
            plannedPackages = setOf("confirmed", "enabled-again", "missing"),
            installedPackages = setOf("confirmed", "enabled-again"),
            disabledPackages = setOf("confirmed")
        )

        assertEquals(setOf("confirmed"), result.confirmedDisabledPackages)
        assertEquals(setOf("enabled-again", "missing"), result.notConfirmedPackages)
    }

    @Test
    fun cleanupFinalSnapshotCanConfirmPackageAfterAnUncertainCommandResult() {
        val result = TvAutomationEngine.verifyCleanupState(
            plannedPackages = setOf("actually-disabled"),
            installedPackages = setOf("actually-disabled"),
            disabledPackages = setOf("actually-disabled")
        )

        assertEquals(setOf("actually-disabled"), result.confirmedDisabledPackages)
        assertTrue(result.notConfirmedPackages.isEmpty())
    }

    @Test
    fun cleanupFinalSnapshotRejectsDisabledStateForUninstalledPackage() {
        val result = TvAutomationEngine.verifyCleanupState(
            plannedPackages = setOf("removed"),
            installedPackages = emptySet(),
            disabledPackages = setOf("removed")
        )

        assertTrue(result.confirmedDisabledPackages.isEmpty())
        assertEquals(setOf("removed"), result.notConfirmedPackages)
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
    fun cleanupHistoryUpdatePreservesBeforeAndAfterMeasurementsAndWarnings() {
        val original = MaintenanceRunHistoryEntry(
            createdAt = 123L,
            deviceIdentity = "tv-test",
            memoryBefore = 42,
            memoryAfter = 55,
            storageBefore = 91,
            storageAfter = 84,
            storageAvailableBytesBefore = 100_000_000L,
            storageAvailableBytesAfter = 120_000_000L,
            cacheTrimAttempted = true,
            cacheTrimOutcome = "Кэш очищен",
            detectedCandidates = 8,
            approvedCandidates = 3,
            reviewCandidates = 4,
            blockedCandidates = 1,
            warnings = listOf("После очистки осталось предупреждение"),
            warningsBefore = listOf("До очистки было предупреждение"),
            cleanupOutcome = "Ожидает очистки",
            changedPackages = emptyList()
        )

        val updated = original.withCleanupResult("Отключено 2 пакета", listOf("com.example.one", "com.example.two"))

        assertEquals(100_000_000L, updated.storageAvailableBytesBefore)
        assertEquals(120_000_000L, updated.storageAvailableBytesAfter)
        assertEquals(listOf("До очистки было предупреждение"), updated.warningsBefore)
        assertEquals(listOf("После очистки осталось предупреждение"), updated.warnings)
        assertEquals(42, updated.memoryBefore)
        assertEquals(55, updated.memoryAfter)
        assertEquals("Отключено 2 пакета", updated.cleanupOutcome)
        assertEquals(listOf("com.example.one", "com.example.two"), updated.changedPackages)
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
