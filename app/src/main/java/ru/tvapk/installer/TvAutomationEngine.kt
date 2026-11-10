package ru.tvapk.installer

/**
 * Deterministic, side-effect-free planning stage for one-tap TV maintenance.
 * It never performs ADB operations; execution remains a separate, verified step.
 */
internal object CleanupJournalValidator {
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
        if (restoredPackages.any { it !in plannedPackages } || restoredPackages.distinct().size != restoredPackages.size) return false
        if (cacheTrimState !in setOf("not_requested", "running", "completed", "failed")) return false
        return true
    }
}

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
        val match = Regex("""(?m)^\S+\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)%\s+/data\s*$""")
            .find(output) ?: return null
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
