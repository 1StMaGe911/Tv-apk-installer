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
        val memoryPercent = if (memoryTotal != null && memoryTotal > 0 && memoryAvailable != null) {
            ((memoryAvailable * 100L) / memoryTotal).toInt().coerceIn(0, 100)
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
