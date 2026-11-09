package ru.tvapk.installer

/**
 * Deterministic, side-effect-free planning stage for one-tap TV maintenance.
 * It never performs ADB operations; execution remains a separate, verified step.
 */
internal object TvAutomationEngine {
    internal data class Plan(
        val detectedCount: Int,
        val approvedCount: Int,
        val reviewCount: Int,
        val blockedCount: Int,
        val alreadyDisabledCount: Int,
        val safeToDisable: Set<String>
    )

    internal fun buildPlan(
        installedPackages: Set<String>,
        disabledPackages: Set<String>,
        detectedCandidates: Set<String>,
        profileApprovedPackages: Set<String>,
        protectedPackages: Set<String>,
        neverDisablePackages: Set<String>
    ): Plan {
        val installedCandidates = detectedCandidates intersect installedPackages
        val alreadyDisabled = installedCandidates intersect disabledPackages
        val eligible = installedCandidates - alreadyDisabled
        val protected = eligible intersect (protectedPackages + neverDisablePackages)
        val profileApproved = eligible intersect profileApprovedPackages
        val safe = (profileApproved - protected).toSortedSet()
        val review = eligible - profileApproved - protected
        val blocked = protected + (eligible intersect protectedPackages)
        return Plan(
            detectedCount = installedCandidates.size,
            approvedCount = safe.size,
            reviewCount = review.size,
            blockedCount = blocked.size,
            alreadyDisabledCount = alreadyDisabled.size,
            safeToDisable = safe
        )
    }
}
