package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAutomationEngineTest {
    @Test
    fun exactModelProfileIsRejectedWhenKnownManufacturerConflicts() {
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("Sony", listOf("Sony Corporation", "BRAVIA")))
        assertFalse(TvAutomationEngine.isProfileBrandCompatible("Sony", listOf("TCL", "FFALCON")))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("Sony", emptyList()))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("Sony", listOf("неизвестно", "unknown")))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("", listOf("TCL")))
    }

    @Test
    fun shortOemBrandMatchesWholeTokenWithoutSubstringFalsePositive() {
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("LG Electronics")))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("LG")))
        assertFalse(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("bulge")))
        assertFalse(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("TCL Electronics")))
        assertFalse(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("HP")))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("unknown", "неизвестно")))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("LG", listOf("N/A")))
        assertTrue(TvAutomationEngine.isProfileBrandCompatible("Сбер ТВ", listOf("ООО СберТВ")))
        assertFalse(TvAutomationEngine.isProfileBrandCompatible("Сбер ТВ", listOf("TCL Electronics")))
    }

    @Test
    fun profileBrandNormalizationPreservesLocalizedLettersAndSeparators() {
        assertEquals("сбертв", TvAutomationEngine.normalizeProfileBrandIdentifier("Сбер ТВ"))
        assertEquals("sonycorporation", TvAutomationEngine.normalizeProfileBrandIdentifier("Sony Corporation"))
        assertTrue(TvAutomationEngine.matchesReportedProfileBrand("Сбер ТВ", listOf("ООО СберТВ")))
        assertTrue(TvAutomationEngine.matchesReportedProfileBrand("Sony", listOf("SONY-Corporation")))
        assertFalse(TvAutomationEngine.matchesReportedProfileBrand("Sony", listOf("TCL Electronics")))
    }

    @Test
    fun exactModelProfileRejectsKnownAndroidSdkOutsideSupportedRange() {
        assertTrue(TvAutomationEngine.isProfileSdkCompatible(34, 33, 35))
        assertFalse(TvAutomationEngine.isProfileSdkCompatible(32, 33, 35))
        assertFalse(TvAutomationEngine.isProfileSdkCompatible(36, 33, 35))
        assertTrue(TvAutomationEngine.isProfileSdkCompatible(null, 33, 35))
        assertTrue(TvAutomationEngine.isProfileSdkCompatible(34, null, null))
    }

    @Test
    fun exactHardwareIdentifiersIgnoreCaseAndSeparatorsButRequireFullMatch() {
        assertTrue(TvAutomationEngine.isSameHardwareIdentifier("BRAVIA-XR-55A80L", "bravia_xr 55a80l"))
        assertTrue(TvAutomationEngine.isSameHardwareIdentifier("Модель ТВ-2026", "модель_тв 2026"))
        assertFalse(TvAutomationEngine.isSameHardwareIdentifier("TV-123", "TV-1234"))
        assertFalse(TvAutomationEngine.isSameHardwareIdentifier("TV-1234", "TV-123"))
        assertFalse(TvAutomationEngine.isSameHardwareIdentifier("", ""))
        assertFalse(TvAutomationEngine.isSameHardwareIdentifier("   ", "TV-123"))
        assertFalse(TvAutomationEngine.isSameHardwareIdentifier("TV-123", ""))
    }

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
    fun curatedGlobalAdAndTrackingPackagesCanBeApprovedWithoutModelProfile() {
        val globalFacts = setOf("com.vendor.ads", "com.vendor.telemetry")
        assertEquals(
            globalFacts,
            TvAutomationEngine.automaticallyApprovedPackages(
                packageReviewFlags = emptyMap(),
                trustedProfile = false,
                knownGlobalPackages = globalFacts
            )
        )
    }

    @Test
    fun globalPackageApprovalStillRespectsProtectedAndNeverDisableLists() {
        val approved = TvAutomationEngine.automaticallyApprovedPackages(
            packageReviewFlags = emptyMap(),
            trustedProfile = false,
            knownGlobalPackages = setOf("com.vendor.ads", "com.android.tv.settings")
        )
        val plan = TvAutomationEngine.buildPlan(
            installedPackages = approved,
            disabledPackages = emptySet(),
            detectedCandidates = approved,
            profileApprovedPackages = approved,
            protectedPackages = setOf("com.android.tv.settings"),
            neverDisablePackages = setOf("com.vendor.ads")
        )

        assertTrue(plan.safeToDisable.isEmpty())
        assertEquals(2, plan.blockedCount)
    }

    @Test
    fun cleanupPlanEnforcesCoreProtectionEvenWhenCallerOmitsProtectedSnapshot() {
        val protectedCore = setOf(
            "com.google.android.gms.persistent",
            "com.android.systemui.navigationbar",
            "com.google.android.overlay.modules.permissioncontroller"
        )
        val plan = TvAutomationEngine.buildPlan(
            installedPackages = protectedCore + "com.vendor.ads",
            disabledPackages = emptySet(),
            detectedCandidates = protectedCore + "com.vendor.ads",
            profileApprovedPackages = protectedCore + "com.vendor.ads",
            protectedPackages = emptySet(),
            neverDisablePackages = emptySet()
        )

        assertEquals(setOf("com.vendor.ads"), plan.safeToDisable)
        assertEquals(protectedCore.size, plan.blockedCount)
    }

    @Test
    fun cleanupPlanCoreProtectionCannotBeOverriddenByGlobalApproval() {
        val corePackage = "com.google.android.tvlauncher"
        val plan = TvAutomationEngine.buildPlan(
            installedPackages = setOf(corePackage),
            disabledPackages = emptySet(),
            detectedCandidates = setOf(corePackage),
            profileApprovedPackages = setOf(corePackage),
            protectedPackages = emptySet(),
            neverDisablePackages = emptySet()
        )

        assertTrue(plan.safeToDisable.isEmpty())
        assertEquals(1, plan.blockedCount)
    }

    @Test
    fun cleanupCandidateClassifierRecognizesReviewedCatalogCategoriesCaseInsensitively() {
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.component", factCategory = "ads"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.component", factCategory = " TRACKING "))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.component", factCategory = "demo"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.component", factCategory = "preloads"))
    }

    @Test
    fun cleanupCandidateClassifierUsesModelProfileCategoryWhenFactsAreMissing() {
        assertTrue(TvAutomationEngine.isCleanupCandidate(
            packageName = "com.vendor.component",
            profileCategory = "tracking"
        ))
    }

    @Test
    fun cleanupCandidateClassifierKeepsPackageNameFallbackCaseInsensitive() {
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.AdvertService"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.analytics"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.promo"))
    }

    @Test
    fun cleanupCandidateClassifierDoesNotMatchUnrelatedSubstringsInsidePackageComponents() {
        assertFalse(TvAutomationEngine.isCleanupCandidate("com.vendor.downloads"))
        assertFalse(TvAutomationEngine.isCleanupCandidate("com.vendor.downloadservice"))
        assertFalse(TvAutomationEngine.isCleanupCandidate("com.vendor.adidas"))
        assertFalse(TvAutomationEngine.isCleanupCandidate("com.vendor.loadsdk"))
    }

    @Test
    fun cleanupCandidateClassifierRecognizesMarkersAtComponentBoundaries() {
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.AdServices"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.adid"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.vendor.partner_ads_service"))
        assertTrue(TvAutomationEngine.isCleanupCandidate("com.ads.provider"))
    }

    @Test
    fun cleanupCandidateClassifierExcludesProtectedAndNeverDisablePackages() {
        assertFalse(TvAutomationEngine.isCleanupCandidate(
            packageName = "com.google.android.tvlauncher",
            factCategory = "ads"
        ))
        assertFalse(TvAutomationEngine.isCleanupCandidate(
            packageName = "com.vendor.component",
            factCategory = "tracking",
            neverDisablePackages = setOf("com.vendor.component")
        ))
    }

    @Test
    fun cleanupCandidateClassifierDoesNotTreatUnreviewedCategoriesAsAutomaticCandidates() {
        val unreviewedCategories = setOf(
            "streaming", "brand", "other", "screensaver",
            "updates", "accessibility", "casting", "voice", "home"
        )
        unreviewedCategories.forEach { category ->
            assertFalse(
                "Category $category must require manual review",
                TvAutomationEngine.isCleanupCandidate(
                    packageName = "com.vendor.video",
                    factCategory = category
                )
            )
            assertFalse(
                "Profile category $category must require manual review",
                TvAutomationEngine.isCleanupCandidate(
                    packageName = "com.vendor.video",
                    profileCategory = category
                )
            )
        }
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
    fun apkStoragePreflightRequiresAtLeastTwoApkCopiesAndTwoHundredMiB() {
        assertEquals(200L * 1024L * 1024L, TvAutomationEngine.requiredStorageBytesForApk(1L))
        assertEquals(400L * 1024L * 1024L, TvAutomationEngine.requiredStorageBytesForApk(200L * 1024L * 1024L))
        assertNull(TvAutomationEngine.requiredStorageBytesForApk(0L))
        assertNull(TvAutomationEngine.requiredStorageBytesForApk(-1L))
        assertNull(TvAutomationEngine.requiredStorageBytesForApk(Long.MAX_VALUE))
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
    fun storageDeltaReportsActualFreeSpaceChangeAndUnknownMeasurements() {
        assertEquals("+12.5 МБ", TvAutomationEngine.formatStorageDelta(10L * 1048576L, (22L * 1048576L) + 512L * 1024L))
        assertEquals("−3.0 МБ", TvAutomationEngine.formatStorageDelta(10L * 1048576L, 7L * 1048576L))
        assertEquals("0 МБ", TvAutomationEngine.formatStorageDelta(10L, 10L))
        assertEquals("н/д", TvAutomationEngine.formatStorageDelta(null, 10L))
        assertEquals("н/д", TvAutomationEngine.formatStorageDelta(-1L, 10L))
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
    @Test
    fun discoversModernWirelessDebuggingAndLegacyAdbAdvertisements() {
        assertEquals(
            listOf("_adb-tls-connect._tcp.", "_adb._tcp."),
            TvAutomationEngine.adbDiscoveryServiceTypes()
        )
        assertFalse(TvAutomationEngine.adbDiscoveryServiceTypes().contains("_adb-tls-pairing._tcp."))
    }

    @Test
    fun discoveredAddressValidationSupportsIpv4AndIpv6AndRejectsLocalAddresses() {
        val ownAddresses = setOf("192.168.1.10", "2001:db8::10")

        assertEquals(
            "192.168.1.20",
            TvAutomationEngine.usableDiscoveredAddressHost(
                java.net.InetAddress.getByName("192.168.1.20"), ownAddresses
            )
        )
        assertEquals(
            "2001:db8:0:0:0:0:0:20",
            TvAutomationEngine.usableDiscoveredAddressHost(
                java.net.InetAddress.getByName("2001:db8::20"), ownAddresses
            )
        )
        assertNull(
            TvAutomationEngine.usableDiscoveredAddressHost(
                java.net.InetAddress.getByName("2001:db8::10"), ownAddresses
            )
        )
        // Expanded and compressed IPv6 forms represent the same address.
        assertNull(
            TvAutomationEngine.usableDiscoveredAddressHost(
                java.net.InetAddress.getByName("2001:0db8:0000:0000:0000:0000:0000:0010"),
                ownAddresses
            )
        )
        assertNull(
            TvAutomationEngine.usableDiscoveredAddressHost(
                java.net.InetAddress.getByName("127.0.0.1"), ownAddresses
            )
        )
        assertNull(
            TvAutomationEngine.usableDiscoveredAddressHost(
                java.net.InetAddress.getByName("::"), ownAddresses
            )
        )
    }

    @Test
    fun automaticDeviceDiscoverySelectsOnlyOneUnambiguousTv() {
        val tv = TvDevice(name = "Living Room TV", ip = "192.168.1.20", port = 5555)
        assertNull(TvAutomationEngine.uniqueDiscoveredDevice(emptyList()))
        assertEquals(tv, TvAutomationEngine.uniqueDiscoveredDevice(listOf(tv)))
        assertEquals(tv, TvAutomationEngine.uniqueDiscoveredDevice(listOf(
            tv,
            tv.copy(name = "Repeated service announcement")
        )))
        assertEquals(tv, TvAutomationEngine.uniqueDiscoveredDevice(listOf(
            tv,
            tv.copy(name = "Whitespace in address", ip = "  ${tv.ip}  ")
        )))
        assertNull(TvAutomationEngine.uniqueDiscoveredDevice(listOf(
            tv,
            TvDevice(name = "Bedroom TV", ip = "192.168.1.21", port = 5555)
        )))
        assertNull(TvAutomationEngine.uniqueDiscoveredDevice(listOf(
            tv,
            TvDevice(name = "Same host, different ADB port", ip = tv.ip, port = 5556)
        )))
    }

    @Test
    fun readOnlyCommandRetriesTransientFailuresAndReturnsSuccessfulResult() {
        var attempts = 0
        val waits = mutableListOf<Long>()

        val result = TvAutomationEngine.retryReadOnlyCommand(
            maxAttempts = 3,
            retryDelayMillis = 250L,
            wait = { waits += it }
        ) {
            attempts++
            if (attempts < 3) throw IllegalStateException("temporary ADB read failure")
            "diagnostics"
        }

        assertEquals("diagnostics", result)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 250L), waits)
    }

    @Test
    fun readOnlyCommandStopsAfterConfiguredAttemptsAndPropagatesLastError() {
        var attempts = 0
        val waits = mutableListOf<Long>()

        val error = runCatching {
            TvAutomationEngine.retryReadOnlyCommand(
                maxAttempts = 2,
                retryDelayMillis = 100L,
                wait = { waits += it }
            ) {
                attempts++
                throw IllegalStateException("ADB unavailable")
            }
        }.exceptionOrNull()

        assertEquals("ADB unavailable", error?.message)
        assertEquals(2, attempts)
        assertEquals(listOf(100L), waits)
    }

    @Test
    fun readOnlyCommandDoesNotRetryPermanentFailures() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val error = runCatching {
            TvAutomationEngine.retryReadOnlyCommand(
                maxAttempts = 3,
                retryDelayMillis = 100L,
                wait = { waits += it },
                shouldRetry = { it is java.io.IOException }
            ) {
                attempts++
                throw IllegalArgumentException("invalid command")
            }
        }.exceptionOrNull()

        assertEquals("invalid command", error?.message)
        assertEquals(1, attempts)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun readOnlyCommandRetriesOnlyErrorsAcceptedByPredicate() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val result = TvAutomationEngine.retryReadOnlyCommand(
            maxAttempts = 3,
            retryDelayMillis = 100L,
            wait = { waits += it },
            shouldRetry = { it is java.io.IOException }
        ) {
            attempts++
            if (attempts == 1) throw java.io.IOException("temporary transport failure")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(listOf(100L), waits)
    }

    @Test
    fun readOnlyCommandDoesNotWaitWhenFirstAttemptSucceeds() {
        var waits = 0
        val result = TvAutomationEngine.retryReadOnlyCommand(
            maxAttempts = 3,
            wait = { waits++ }
        ) { "ok" }

        assertEquals("ok", result)
        assertEquals(0, waits)
    }

    @Test
    fun readOnlyCommandRejectsUnboundedRetryConfiguration() {
        assertTrue(runCatching {
            TvAutomationEngine.retryReadOnlyCommand(maxAttempts = 6) { "ok" }
        }.isFailure)
        assertTrue(runCatching {
            TvAutomationEngine.retryReadOnlyCommand(retryDelayMillis = -1L) { "ok" }
        }.isFailure)
    }

    @Test
    fun protectsCoreAndroidAndGoogleTvPackagesFromAutomaticCleanup() {
        val protected = listOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.google.android.gsf",
            "com.google.android.tvlauncher",
            "com.google.android.overlay.modules.permissioncontroller"
        )
        protected.forEach { assertTrue("Expected protected package: $it", TvAutomationEngine.isProtectedSystemPackage(it)) }
    }

    @Test
    fun protectsFireTvHomeAndSystemProviderPackages() {
        assertTrue(TvAutomationEngine.isProtectedSystemPackage("com.amazon.tv.launcher"))
        assertTrue(TvAutomationEngine.isProtectedSystemPackage("com.amazon.firehomestarter"))
        assertTrue(TvAutomationEngine.isProtectedSystemPackage("com.android.providers.tv"))
    }

    @Test
    fun doesNotOverprotectExplicitlyAllowedProvidersOrThirdPartyApps() {
        assertFalse(TvAutomationEngine.isProtectedSystemPackage("com.android.providers.calendar"))
        assertFalse(TvAutomationEngine.isProtectedSystemPackage("com.android.providers.contacts"))
        assertFalse(TvAutomationEngine.isProtectedSystemPackage("com.vendor.adservice"))
    }
    @Test
    fun combinedLauncherFlowContinuesOnlyAfterVerifiedCleanupSuccess() {
        assertTrue(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Отключено: 4 из 4. Ошибок: 0\nИтоговая проверка на ТВ: 4 из 4"
        ))
        assertTrue(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Отключено: 0 из 0. Ошибок: 0\nИтоговая проверка на ТВ: 0 из 0"
        ))
        assertTrue(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Очистка кэша выполнена: удалено 12 МБ"
        ))
        assertFalse(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Отключено: 3 из 4. Ошибок: 0\nИтоговая проверка на ТВ: 3 из 4"
        ))
        assertFalse(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Отключено: 0 из 4. Ошибок: 0\nИтоговая проверка на ТВ: 0 из 4"
        ))
        assertFalse(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Отключено: 3 из 4. Ошибок: 1\nНе удалось отключить: package.one"
        ))
        assertFalse(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Очистка кэша не выполнена: команда запрещена"
        ))
        assertFalse(TvAutomationEngine.cleanupResultAllowsLauncherInstall(
            "Ошибка: устройство не отвечает"
        ))
    }

    @Test
    fun oneButtonCleanupAutomaticallyRunsOnlyThePreApprovedPackagePlan() {
        assertTrue(TvAutomationEngine.shouldAutoExecuteApprovedCleanup(
            setOf("com.vendor.ads", "com.vendor.tracking")
        ))
        assertTrue(TvAutomationEngine.shouldAutoExecuteApprovedCleanup(
            setOf("com.vendor.ads")
        ))
        assertFalse(TvAutomationEngine.shouldAutoExecuteApprovedCleanup(emptySet()))
    }

    @Test
    fun cleanupApprovalPreferenceKeyIsStableAndDeviceScoped() {
        val first = TvAutomationEngine.cleanupApprovalPreferenceKey(
            "Google|Model-A|device-a|fingerprint-a"
        )
        assertEquals(first, TvAutomationEngine.cleanupApprovalPreferenceKey(
            "Google|Model-A|device-a|fingerprint-a"
        ))
        assertFalse(first == TvAutomationEngine.cleanupApprovalPreferenceKey(
            "Google|Model-B|device-b|fingerprint-b"
        ))
        assertTrue(first.startsWith("cleanup_approved_packages_v1_"))
        assertFalse(first.contains("fingerprint-a"))
    }

    @Test
    fun cleanupApprovalPreferenceKeyRejectsUnknownIdentity() {
        assertTrue(runCatching {
            TvAutomationEngine.cleanupApprovalPreferenceKey(" ")
        }.isFailure)
        assertTrue(runCatching {
            TvAutomationEngine.cleanupApprovalPreferenceKey("неизвестно")
        }.isFailure)
        assertTrue(runCatching {
            TvAutomationEngine.cleanupApprovalPreferenceKey("Brand|Model|device|неизвестно")
        }.isFailure)
    }


    @Test
    fun resumePlannerEnforcesCoreProtectionEvenWhenCallerOmitsProtectedSnapshot() {
        val corePackages = setOf(
            "com.google.android.gms.persistent",
            "com.android.systemui.navigationbar",
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.tvlauncher"
        )
        val candidates = corePackages + "com.vendor.ads"
        val result = TvAutomationEngine.buildCleanupResumePlan(
            retryablePackages = candidates,
            installedPackages = candidates,
            disabledPackages = emptySet(),
            profileApprovedPackages = candidates,
            protectedPackages = emptySet(),
            neverDisablePackages = emptySet(),
            baselineDisabledPackages = emptySet(),
            restoredPackages = emptySet()
        )

        assertEquals(setOf("com.vendor.ads"), result.safeToRetryPackages)
        assertEquals(corePackages, result.rejectedPackages)
    }


    @Test
    fun packageListParserAcceptsEmptyOptionalSnapshots() {
        assertTrue(TvAutomationEngine.parsePackageListOutput("", allowEmpty = true).isEmpty())
        assertTrue(TvAutomationEngine.parsePackageListOutput("\n  \n", allowEmpty = true).isEmpty())
    }

    @Test
    fun packageListParserAcceptsAndroidFrameworkPackage() {
        assertEquals(
            setOf("android", "com.android.settings"),
            TvAutomationEngine.parsePackageListOutput(
                "package:android\npackage:com.android.settings\n",
                allowEmpty = false
            )
        )
    }

    @Test
    fun packageListParserRequiresInstalledSnapshotToBeNonEmpty() {
        assertTrue(runCatching {
            TvAutomationEngine.parsePackageListOutput("", allowEmpty = false)
        }.isFailure)
    }

    @Test
    fun packageListParserNormalizesAndDeduplicatesValidPackageRows() {
        assertEquals(
            setOf("com.example.tv", "org.example.player"),
            TvAutomationEngine.parsePackageListOutput(
                "package:com.example.tv\n\n package:org.example.player \npackage:com.example.tv\n",
                allowEmpty = false
            )
        )
    }

    @Test
    fun packageListParserRejectsCommandErrorsInsteadOfReturningAnEmptySnapshot() {
        assertTrue(runCatching {
            TvAutomationEngine.parsePackageListOutput("Error: unknown option -d", allowEmpty = true)
        }.isFailure)
        assertTrue(runCatching {
            TvAutomationEngine.parsePackageListOutput("package:not a package", allowEmpty = true)
        }.isFailure)
    }

    @Test
    fun packageListParserRejectsOversizedAndExcessiveOutput() {
        assertTrue(runCatching {
            TvAutomationEngine.parsePackageListOutput("x".repeat(1_000_001), allowEmpty = true)
        }.isFailure)
        val tooManyLines = (1..10_001).joinToString("\n") { " " }
        assertTrue(runCatching {
            TvAutomationEngine.parsePackageListOutput(tooManyLines, allowEmpty = true)
        }.isFailure)
    }
}
