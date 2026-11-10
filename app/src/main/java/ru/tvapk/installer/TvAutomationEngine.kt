package ru.tvapk.installer

/**
 * Deterministic, side-effect-free planning stage for one-tap TV maintenance.
 * It never performs ADB operations; execution remains a separate, verified step.
 */
internal object CleanupJournalValidator {
    internal fun areStringValues(values: List<Any?>): Boolean = values.all { it is String }

    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    internal fun isValid(
        deviceIdentity: String,
        createdAt: Long,
        schemaVersion: Int,
        plannedPackages: List<String>,
        completedPackages: List<String>,
        inFlightPackage: String? = null,
        baselineDisabledPackages: List<String> = emptyList(),
        restoredPackages: List<String> = emptyList(),
        cacheTrimState: String = "not_requested"
    ): Boolean {
        if (deviceIdentity.isBlank() || createdAt <= 0L || schemaVersion !in 1..3 || plannedPackages.isEmpty()) return false
        if (plannedPackages.any { !packageNamePattern.matches(it) } || plannedPackages.distinct().size != plannedPackages.size) return false
        if (completedPackages.any { it !in plannedPackages } || completedPackages.distinct().size != completedPackages.size) return false
        if (inFlightPackage != null && inFlightPackage !in plannedPackages) return false
        if (baselineDisabledPackages.any { !packageNamePattern.matches(it) } ||
            baselineDisabledPackages.distinct().size != baselineDisabledPackages.size) return false
        // A package disabled before maintenance must never be treated as changed or restored by this operation.
        if (baselineDisabledPackages.any { it in plannedPackages }) return false
        if (restoredPackages.any { it !in plannedPackages } || restoredPackages.distinct().size != restoredPackages.size) return false
        if (restoredPackages.any { it in baselineDisabledPackages }) return false
        if (cacheTrimState !in setOf("not_requested", "running", "completed", "failed")) return false
        return true
    }
}

internal object TvAutomationEngine {
    /** Auto-select only when LAN discovery found exactly one ADB device. */
    internal fun uniqueDiscoveredDevice(devices: List<TvDevice>): TvDevice? {
        // NSD can report the same endpoint more than once (for example, repeated
        // service announcements). Count unique ADB endpoints, not announcements.
        val uniqueEndpoints = devices.distinctBy { it.ip.trim() to it.port }
        return uniqueEndpoints.singleOrNull()
    }

    /** Retries only explicitly read-only operations; callers must never pass mutations here. */
    internal fun <T> retryReadOnlyCommand(
        maxAttempts: Int = 3,
        retryDelayMillis: Long = 350L,
        wait: (Long) -> Unit = { Thread.sleep(it) },
        shouldRetry: (Exception) -> Boolean = { true },
        action: () -> T
    ): T {
        require(maxAttempts in 1..5) { "maxAttempts must be between 1 and 5" }
        require(retryDelayMillis >= 0L) { "retryDelayMillis must not be negative" }
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return action()
            } catch (error: Exception) {
                lastError = error
                if (attempt == maxAttempts || !shouldRetry(error)) throw error
                if (retryDelayMillis > 0L) wait(retryDelayMillis)
            }
        }
        throw lastError ?: IllegalStateException("Read-only command failed without an exception")
    }

    /** Explicit interactive gate shared by exported restore scripts. */
    internal fun portableRestoreConfirmationGuard(shell: String): List<String> = when (shell) {
        "bash" -> listOf(
            "printf '%s' 'Восстановление изменит состояние пакетов и автозапуска. Введи RESTORE для продолжения: '",
            "read -r confirmation",
            "[[ \$confirmation == 'RESTORE' ]] || { echo 'Отменено: подтверждение не получено. Изменений нет.' >&2; exit 4; }"
        )
        "powershell" -> listOf(
            "\$confirmation = Read-Host 'Восстановление изменит состояние пакетов и автозапуска. Введи RESTORE для продолжения'",
            "if (\$confirmation -cne 'RESTORE') { throw 'Отменено: подтверждение не получено. Изменений нет.' }"
        )
        else -> emptyList()
    }
    internal enum class ManualPackageRollbackDecision {
        MISSING,
        ALREADY_ORIGINAL,
        RESTORE,
        CONFLICT
    }

    /** Classifies a journal entry against a fresh device snapshot before rollback. */
    internal fun manualPackageRollbackDecision(
        installed: Boolean,
        currentDisabled: Boolean,
        wasDisabled: Boolean,
        isDisabled: Boolean
    ): ManualPackageRollbackDecision = when {
        !installed -> ManualPackageRollbackDecision.MISSING
        wasDisabled == isDisabled -> ManualPackageRollbackDecision.CONFLICT
        currentDisabled == wasDisabled -> ManualPackageRollbackDecision.ALREADY_ORIGINAL
        currentDisabled == isDisabled -> ManualPackageRollbackDecision.RESTORE
        else -> ManualPackageRollbackDecision.CONFLICT
    }

    /** Returns the unique index of the highest-scoring candidate; ties fail closed. */
    internal fun uniqueBestCandidateIndex(scores: List<Int>): Int? {
        if (scores.isEmpty()) return null
        val bestScore = scores.maxOrNull() ?: return null
        val bestIndices = scores.indices.filter { scores[it] == bestScore }
        return bestIndices.singleOrNull()
    }

    /** Only the bundled trusted profile baseline may authorize automatic package changes. */
    internal fun automaticallyApprovedPackages(
        packageReviewFlags: Map<String, Boolean>,
        trustedProfile: Boolean
    ): Set<String> = if (!trustedProfile) {
        emptySet()
    } else {
        packageReviewFlags.filterValues { asksForReview -> !asksForReview }.keys
    }

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

    internal data class CleanupRecoveryPlan(
        val confirmedCompletedPackages: Set<String>,
        val retryablePackages: Set<String>,
        val conflictPackages: Set<String>
    )

    /**
     * Reconciles an interrupted journal with a fresh Package Manager snapshot.
     * A package is never retried if it was previously marked complete but is now enabled.
     * Packages disabled before this operation or already restored are excluded.
     */
    internal fun buildCleanupRecoveryPlan(
        plannedPackages: Set<String>,
        completedPackages: Set<String>,
        installedPackages: Set<String>,
        disabledPackages: Set<String>,
        baselineDisabledPackages: Set<String>,
        restoredPackages: Set<String>
    ): CleanupRecoveryPlan {
        val confirmed = sortedSetOf<String>()
        val retryable = sortedSetOf<String>()
        val conflicts = sortedSetOf<String>()
        plannedPackages.forEach { packageName ->
            if (packageName in baselineDisabledPackages) return@forEach
            if (packageName in restoredPackages) {
                if (packageName in disabledPackages) conflicts += packageName
                return@forEach
            }
            when {
                packageName !in installedPackages -> conflicts += packageName
                packageName in disabledPackages && packageName in completedPackages -> confirmed += packageName
                packageName in disabledPackages -> conflicts += packageName
                packageName in completedPackages -> conflicts += packageName
                else -> retryable += packageName
            }
        }
        return CleanupRecoveryPlan(confirmed, retryable, conflicts)
    }

    internal data class CleanupResumePlan(
        val safeToRetryPackages: Set<String>,
        val rejectedPackages: Set<String>
    )

    /**
     * Selects only interrupted packages that remain installed, enabled, explicitly
     * approved by the exact local device profile, and outside all protected sets.
     * Remote profile data must never be passed as approved packages.
     */
    internal fun buildCleanupResumePlan(
        retryablePackages: Set<String>,
        installedPackages: Set<String>,
        disabledPackages: Set<String>,
        profileApprovedPackages: Set<String>,
        protectedPackages: Set<String>,
        neverDisablePackages: Set<String>,
        baselineDisabledPackages: Set<String>,
        restoredPackages: Set<String>
    ): CleanupResumePlan {
        val safe = sortedSetOf<String>()
        val rejected = sortedSetOf<String>()
        retryablePackages.forEach { packageName ->
            if (
                packageName in installedPackages &&
                packageName !in disabledPackages &&
                packageName in profileApprovedPackages &&
                packageName !in protectedPackages &&
                packageName !in neverDisablePackages &&
                packageName !in baselineDisabledPackages &&
                packageName !in restoredPackages
            ) {
                safe += packageName
            } else {
                rejected += packageName
            }
        }
        return CleanupResumePlan(safe, rejected)
    }

    internal data class RollbackVerification(
        val restoredPackages: Set<String>,
        val remainingPackages: Set<String>
    )

    /** Reconciles rollback results against the final Package Manager snapshot. */
    internal fun verifyRollbackState(
        rollbackPackages: Set<String>,
        installedPackages: Set<String>,
        disabledPackages: Set<String>
    ): RollbackVerification {
        val restored = (rollbackPackages intersect installedPackages) - disabledPackages
        return RollbackVerification(
            restoredPackages = restored,
            remainingPackages = rollbackPackages - restored
        )
    }

    internal data class CleanupVerification(
        val confirmedDisabledPackages: Set<String>,
        val notConfirmedPackages: Set<String>
    )

    /** Only packages present in the final installed-and-disabled snapshot count as changed. */
    internal fun verifyCleanupState(
        plannedPackages: Set<String>,
        installedPackages: Set<String>,
        disabledPackages: Set<String>
    ): CleanupVerification {
        val confirmed = plannedPackages intersect installedPackages intersect disabledPackages
        return CleanupVerification(
            confirmedDisabledPackages = confirmed,
            notConfirmedPackages = plannedPackages - confirmed
        )
    }

    internal fun shouldAutomaticallyTrimCache(enabled: Boolean, dataUsedPercent: Int?): Boolean =
        enabled && dataUsedPercent != null && dataUsedPercent in 90..100

    internal data class DataStorageSnapshot(
        val totalBytes: Long,
        val usedBytes: Long,
        val availableBytes: Long,
        val usedPercent: Int
    )

    /** Parses machine-readable df -k /data output conservatively. */
    internal fun parseDataStorageSnapshot(output: String): DataStorageSnapshot? {
        // Multiple /data rows make the source ambiguous; fail closed instead of
        // silently selecting whichever mount happens to appear first.
        val matches = Regex("""(?m)^\S+\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)%\s+/data\s*$""")
            .findAll(output).toList()
        if (matches.size != 1) return null
        val match = matches.single()
        val totalKb = match.groupValues[1].toLongOrNull() ?: return null
        val usedKb = match.groupValues[2].toLongOrNull() ?: return null
        val availableKb = match.groupValues[3].toLongOrNull() ?: return null
        val usedPercent = match.groupValues[4].toIntOrNull() ?: return null
        if (totalKb <= 0 || totalKb > Long.MAX_VALUE / 1024L || usedKb < 0 || availableKb < 0 || usedPercent !in 0..100) return null
        if (usedKb > totalKb || availableKb > totalKb || usedKb > totalKb - availableKb) return null
        return DataStorageSnapshot(totalKb * 1024L, usedKb * 1024L, availableKb * 1024L, usedPercent)
    }

    /** Reports the measured change in free /data space; positive means more free space. */
    internal fun formatStorageDelta(beforeBytes: Long?, afterBytes: Long?): String {
        if (beforeBytes == null || afterBytes == null || beforeBytes < 0L || afterBytes < 0L) return "н/д"
        val deltaMb = (afterBytes.toDouble() - beforeBytes.toDouble()) / 1048576.0
        return when {
            deltaMb > 0.0 -> String.format(java.util.Locale.ROOT, "+%.1f МБ", deltaMb)
            deltaMb < 0.0 -> String.format(java.util.Locale.ROOT, "−%.1f МБ", -deltaMb)
            else -> "0 МБ"
        }
    }

    internal data class HealthAssessment(
        val memoryAvailablePercent: Int?,
        val dataUsedPercent: Int?,
        val uptimeDays: Int?,
        val warnings: List<String>,
        val summary: String
    )

    /**
     * Interprets read-only diagnostics conservatively. Missing or unparseable values
     * are omitted rather than treated as failures or healthy measurements.
     */
    internal fun assessHealth(diagnostics: String): HealthAssessment {
        fun number(pattern: Regex): Long? =
            pattern.find(diagnostics)?.groupValues?.getOrNull(1)?.toLongOrNull()

        val memoryTotal = number(Regex("""(?m)^MemTotal:\s*(\d+)\s*kB"""))
        val memoryAvailable = number(Regex("""(?m)^MemAvailable:\s*(\d+)\s*kB"""))
            ?: number(Regex("""(?m)^MemFree:\s*(\d+)\s*kB"""))
        val memoryPercent = if (memoryTotal != null && memoryTotal > 0 && memoryAvailable != null &&
            memoryAvailable >= 0 && memoryAvailable <= memoryTotal
        ) {
            ((memoryAvailable.toDouble() / memoryTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else null

        val dataUsedPercent = Regex("""(?m)^.*\s(\d{1,3})%\s+/data\s*$""")
            .find(diagnostics)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..100 }

        val uptimeSeconds = Regex("""ВРЕМЯ РАБОТЫ\s*\n\s*(\d+(?:\.\d+)?)\s+секунд""", RegexOption.IGNORE_CASE)
            .find(diagnostics)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val uptimeDays = uptimeSeconds?.takeIf { it >= 0 }?.div(86400.0)?.toInt()

        val warnings = buildList {
            if (memoryPercent != null && memoryPercent < 10) {
                add("Мало доступной оперативной памяти: $memoryPercent%. Закрой тяжёлые приложения и повтори проверку.")
            }
            if (dataUsedPercent != null && dataUsedPercent >= 95) {
                add("Внутреннее хранилище почти заполнено: занято $dataUsedPercent%. Очистку хранилища нужно выполнять отдельно и только после просмотра списка.")
            } else if (dataUsedPercent != null && dataUsedPercent >= 90) {
                add("Мало свободного места во внутреннем хранилище: занято $dataUsedPercent%.")
            }
            if (uptimeDays != null && uptimeDays >= 30) {
                add("Телевизор работает без перезагрузки уже $uptimeDays дней. Рекомендуется обычная перезагрузка в удобное время; принудительное завершение приложений не выполнялось.")
            }
        }

        val measured = memoryPercent != null || dataUsedPercent != null
        val summary = when {
            warnings.isNotEmpty() -> "Обнаружены показатели, требующие внимания"
            measured -> "По доступным показателям критических предупреждений нет"
            else -> "Недостаточно данных для оценки ресурсов"
        }
        return HealthAssessment(memoryPercent, dataUsedPercent, uptimeDays, warnings, summary)
    }

}
