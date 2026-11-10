package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAutomationEngineTest {
    @Test
    fun portableRestoreScriptsRequireExactInteractiveConfirmation() {
        assertEquals(3, TvAutomationEngine.portableRestoreConfirmationGuard("bash").size)
        assertTrue(TvAutomationEngine.portableRestoreConfirmationGuard("bash").last().contains("RESTORE"))
        assertEquals(2, TvAutomationEngine.portableRestoreConfirmationGuard("powershell").size)
        assertTrue(TvAutomationEngine.portableRestoreConfirmationGuard("powershell").last().contains("-cne 'RESTORE'"))
        assertTrue(TvAutomationEngine.portableRestoreConfirmationGuard("unknown").isEmpty())
    }

    @Test
    fun manualPackageRollbackDecisionRequiresInstalledPackageAndExpectedState() {
        assertEquals(TvAutomationEngine.ManualPackageRollbackDecision.MISSING, TvAutomationEngine.manualPackageRollbackDecision(
            installed = false, currentDisabled = true, wasDisabled = false, isDisabled = true
        ))
        assertEquals(TvAutomationEngine.ManualPackageRollbackDecision.ALREADY_ORIGINAL, TvAutomationEngine.manualPackageRollbackDecision(
            installed = true, currentDisabled = false, wasDisabled = false, isDisabled = true
        ))
        assertEquals(TvAutomationEngine.ManualPackageRollbackDecision.RESTORE, TvAutomationEngine.manualPackageRollbackDecision(
            installed = true, currentDisabled = true, wasDisabled = false, isDisabled = true
        ))
        // A journal entry that claims no state transition is internally inconsistent.
        assertEquals(TvAutomationEngine.ManualPackageRollbackDecision.CONFLICT, TvAutomationEngine.manualPackageRollbackDecision(
            installed = true, currentDisabled = true, wasDisabled = false, isDisabled = false
        ))
    }

    @Test
    fun uniqueBestCandidateSelectionRejectsTiesAndEmptyInput() {
        assertNull(TvAutomationEngine.uniqueBestCandidateIndex(emptyList()))
        assertEquals(0, TvAutomationEngine.uniqueBestCandidateIndex(listOf(7)))
        assertEquals(1, TvAutomationEngine.uniqueBestCandidateIndex(listOf(3, 9, 4)))
        assertEquals(0, TvAutomationEngine.uniqueBestCandidateIndex(listOf(9, 2, 5, 1)))
        assertNull(TvAutomationEngine.uniqueBestCandidateIndex(listOf(9, 9, 2)))
        assertNull(TvAutomationEngine.uniqueBestCandidateIndex(listOf(1, 8, 8, 8)))
    }

    @Test
    fun remoteOrUntrustedProfilesNeverAuthorizeAutomaticDisabling() {
        val packageFlags = mapOf("com.example.ads" to false, "com.example.tracking" to true)
        assertTrue(TvAutomationEngine.automaticallyApprovedPackages(packageFlags, trustedProfile = false).isEmpty())
    }

    @Test
    fun trustedProfilesApproveOnlyPackagesNotMarkedForReview() {
        val packageFlags = mapOf("com.example.ads" to false, "com.example.tracking" to true)
        assertEquals(
            setOf("com.example.ads"),
            TvAutomationEngine.automaticallyApprovedPackages(packageFlags, trustedProfile = true)
        )
    }

    @Test
    fun cleanupJournalRejectsNonStringArrayValuesBeforeCoercion() {
        assertTrue(CleanupJournalValidator.areStringValues(listOf("com.example.app", "org.example.tv")))
        assertFalse(CleanupJournalValidator.areStringValues(listOf("com.example.app", 42)))
        assertFalse(CleanupJournalValidator.areStringValues(listOf("com.example.app", null)))
        assertTrue(CleanupJournalValidator.areStringValues(emptyList()))
    }

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
            // The caller folds the journal's in-flight package into attempted packages
            // after reconciling it with the live Package Manager snapshot.
            completedPackages = setOf("com.example.done", "com.example.inFlight"),
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
    fun recoveryDoesNotClaimUnattemptedDisabledPackageAsCompleted() {
        val result = TvAutomationEngine.buildCleanupRecoveryPlan(
            plannedPackages = setOf("com.example.pending"),
            completedPackages = emptySet(),
            installedPackages = setOf("com.example.pending"),
            disabledPackages = setOf("com.example.pending"),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )
        assertTrue(result.confirmedCompletedPackages.isEmpty())
        assertEquals(setOf("com.example.pending"), result.conflictPackages)
        assertTrue(result.retryablePackages.isEmpty())
    }

    @Test
    fun recoveryFlagsPreviouslyRestoredPackageThatBecameDisabledAgain() {
        val result = TvAutomationEngine.buildCleanupRecoveryPlan(
            plannedPackages = setOf("com.example.restored"),
            completedPackages = emptySet(),
            installedPackages = setOf("com.example.restored"),
            disabledPackages = setOf("com.example.restored"),
            baselineDisabledPackages = emptySet(),
            restoredPackages = setOf("com.example.restored")
        )
        assertTrue(result.confirmedCompletedPackages.isEmpty())
        assertEquals(setOf("com.example.restored"), result.conflictPackages)
        assertTrue(result.retryablePackages.isEmpty())
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
    fun resumePlannerAllowsOnlyExactProfileApprovedInstalledEnabledPackages() {
        val result = TvAutomationEngine.buildCleanupResumePlan(
            retryablePackages = setOf("com.example.safe", "com.example.review", "com.example.missing"),
            installedPackages = setOf("com.example.safe", "com.example.review"),
            disabledPackages = emptySet(),
            profileApprovedPackages = setOf("com.example.safe"),
            protectedPackages = emptySet(),
            neverDisablePackages = emptySet(),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )

        assertEquals(setOf("com.example.safe"), result.safeToRetryPackages)
        assertEquals(setOf("com.example.review", "com.example.missing"), result.rejectedPackages)
    }

    @Test
    fun resumePlannerRejectsDisabledProtectedNeverDisableBaselineAndRestoredPackages() {
        val candidates = setOf(
            "com.example.disabled", "com.example.protected", "com.example.never",
            "com.example.baseline", "com.example.restored"
        )
        val result = TvAutomationEngine.buildCleanupResumePlan(
            retryablePackages = candidates,
            installedPackages = candidates,
            disabledPackages = setOf("com.example.disabled"),
            profileApprovedPackages = candidates,
            protectedPackages = setOf("com.example.protected"),
            neverDisablePackages = setOf("com.example.never"),
            baselineDisabledPackages = setOf("com.example.baseline"),
            restoredPackages = setOf("com.example.restored")
        )

        assertTrue(result.safeToRetryPackages.isEmpty())
        assertEquals(candidates, result.rejectedPackages)
    }

    @Test
    fun resumePlannerDoesNotTreatProfileApprovalAsPermissionForMissingPackage() {
        val result = TvAutomationEngine.buildCleanupResumePlan(
            retryablePackages = setOf("com.example.missing"),
            installedPackages = emptySet(),
            disabledPackages = emptySet(),
            profileApprovedPackages = setOf("com.example.missing"),
            protectedPackages = emptySet(),
            neverDisablePackages = emptySet(),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )

        assertTrue(result.safeToRetryPackages.isEmpty())
        assertEquals(setOf("com.example.missing"), result.rejectedPackages)
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
    fun rejectsAmbiguousMultipleDataMountRows() {
        val output = """
            /dev/block/dm-1 1000 700 300 70% /data
            /dev/block/dm-2 2000 1500 500 75% /data
        """.trimIndent()
        assertNull(TvAutomationEngine.parseDataStorageSnapshot(output))
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
