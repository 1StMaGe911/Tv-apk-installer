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

internal object PackageListParser {
    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    /** An empty Package Manager result is valid: it means no packages matched the query. */
    internal fun parse(raw: String): Set<String> =
        raw.lineSequence()
            .map { it.trim().removePrefix("package:").trim() }
            .filter { packageNamePattern.matches(it) }
            .toSet()
}

internal object TvAutomationEngine {
internal fun isProtectedSystemPackage(packageName: String): Boolean {
    // Core Android / TV middleware: upstream Clean Your TV recovery rules prohibit
    // disabling these because a TV can lose setup, networking, package management or boot.
    val protected = setOf(
        "android",
        "android.ext.services",
        "android.ext.shared",
        "com.android.sdksandbox",
        "com.android.location.fused",
        "com.android.systemui",
        "com.android.shell",
        "com.android.phone",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.providers.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.ext.services",
        "com.android.ext.shared",
        "com.android.modulemetadata",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.ext.services",
        "com.google.android.ext.shared",
        "com.google.android.modulemetadata",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.webview",
        "com.google.android.tvlauncher",
        "com.google.android.tv",
        "com.android.vending",
        "com.cltv.mal",
        "com.mediatek.tis",
        "com.mediatek.tv.service",
        "com.mediatek.tv.agent",
        // Fire OS protects its own Home apps; disabling either can leave the TV without a usable Home.
        "com.amazon.tv.launcher",
        "com.amazon.firehomestarter"
    )
    val allowedProviders = setOf(
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.providers.userdictionary",
        "com.android.providers.blockednumber"
    )
    return packageName in protected ||
        (packageName.startsWith("com.android.providers.") && packageName !in allowedProviders) ||
        packageName.startsWith("com.android.systemui.") ||
        packageName.startsWith("com.google.android.gms.") ||
        packageName.startsWith("com.google.android.overlay.modules")
}

    /**
     * Shared candidate classifier for bundled facts and model metadata.
     * Category labels are normalized, while package-name markers remain a
     * fallback for packages absent from the curated catalog.
     */
    internal fun isCleanupCandidate(
        packageName: String,
        factCategory: String? = null,
        profileCategory: String? = null,
        neverDisablePackages: Set<String> = emptySet()
    ): Boolean {
        if (packageName.isBlank() || packageName in neverDisablePackages ||
            isProtectedSystemPackage(packageName)
        ) return false

        val reviewedCategories = setOf("ads", "tracking", "demo", "preloads")
        fun normalizedCategory(value: String?): String? =
            value?.trim()?.lowercase(java.util.Locale.ROOT)

        if (normalizedCategories(factCategory, profileCategory).any { it in reviewedCategories }) {
            return true
        }

        // Match package components, not arbitrary substrings: "downloads" and
        // "adidas" are unrelated names. Prefix matching retains common service names.
        val components = packageName.lowercase(java.util.Locale.ROOT)
            .split(Regex("[._]"))
            .filter { it.isNotBlank() }
        val markers = listOf(
            "ads", "adservice", "adservices", "adid", "advert",
            "tracking", "analytics", "promo", "promotion", "feedback", "demo"
        )
        return components.any { component ->
            markers.any { marker ->
                if (marker == "adid") component == marker
                else component.startsWith(marker)
            }
        }
    }

    /** Normalizes OEM labels without dropping letters from localized manufacturer names. */
    internal fun normalizeProfileBrandIdentifier(value: String): String =
        value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Matches a reviewed profile brand against reported manufacturer/product-brand fields. */
    internal fun matchesReportedProfileBrand(profileBrand: String, reportedBrands: List<String>): Boolean {
        val target = normalizeProfileBrandIdentifier(profileBrand)
        if (target.length < 3) return false
        return reportedBrands.asSequence()
            .map(::normalizeProfileBrandIdentifier)
            .filter { it.length >= 3 }
            .any { it.contains(target) || target.contains(it) }
    }

    /** Rejects an exact model/device profile when known OEM or Android version conflicts. */
    internal fun isProfileBrandCompatible(profileBrand: String, reportedBrands: List<String>): Boolean {
        val expected = normalizeProfileBrandIdentifier(profileBrand)
        if (expected.isBlank()) return true
        val unknownValues = setOf("", "unknown", "unknown0", "неизвестно", "na", "null", "none")
            .map(::normalizeProfileBrandIdentifier).toSet()
        val meaningfulBrands = reportedBrands.map(::normalizeProfileBrandIdentifier)
            .filter { it.isNotBlank() && it !in unknownValues }
        if (meaningfulBrands.isEmpty()) return true

        // Short OEM marks such as "LG" must match a complete token, not a
        // substring (which would incorrectly match words such as "bulge").
        if (expected.length < 3) {
            val reportedTokens = reportedBrands.flatMap { brand ->
                brand.lowercase(java.util.Locale.ROOT)
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .map(::normalizeProfileBrandIdentifier)
            }.filter { it.isNotBlank() }
            return expected in reportedTokens
        }
        return meaningfulBrands.any { it.contains(expected) || expected.contains(it) }
    }

    /** Missing SDK data does not invent a mismatch; known out-of-range SDK always rejects. */
    internal fun isProfileSdkCompatible(sdk: Int?, sdkMin: Int?, sdkMax: Int?): Boolean {
        if (sdk == null) return true
        if (sdkMin != null && sdk < sdkMin) return false
        if (sdkMax != null && sdk > sdkMax) return false
        return true
    }

    /**
     * Compares complete hardware identifiers while ignoring case and separators.
     * Unicode letters and digits are retained; empty identifiers never match.
     */
    internal fun isSameHardwareIdentifier(expected: String, actual: String): Boolean {
        fun canonical(value: String): String = value.trim()
            .lowercase(java.util.Locale.ROOT)
            .filter { it.isLetterOrDigit() }
        val expectedId = canonical(expected)
        val actualId = canonical(actual)
        return expectedId.isNotEmpty() && expectedId == actualId
    }

    private fun normalizedCategories(vararg categories: String?): List<String> =
        categories.mapNotNull { it?.trim()?.lowercase(java.util.Locale.ROOT) }

    /** Modern wireless debugging plus legacy ADB-over-network DNS-SD advertisements. */
    internal fun adbDiscoveryServiceTypes(): List<String> = listOf(
        "_adb-tls-connect._tcp.",
        "_adb._tcp."
    )

    /** Keeps usable IPv4 and IPv6 DNS-SD endpoints while excluding this phone and unusable addresses. */
    internal fun usableDiscoveredAddressHost(
        address: java.net.InetAddress,
        ownAddresses: Set<String>
    ): String? {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return null
        val host = address.hostAddress ?: return null
        // Compare address bytes rather than textual forms: IPv6 has multiple equivalent
        // spellings (compressed/expanded), and scoped link-local addresses include a zone.
        val isOwnAddress = ownAddresses.any { own ->
            runCatching {
                java.net.InetAddress.getByName(own.substringBefore('%')).address
                    .contentEquals(address.address)
            }.getOrDefault(false)
        }
        return host.takeUnless { isOwnAddress }
    }

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
        trustedProfile: Boolean,
        knownGlobalPackages: Set<String> = emptySet()
    ): Set<String> {
        // The model-specific profile is optional for exact package IDs present in
        // the app-bundled, curated facts catalog. The caller must not pass packages
        // from an untrusted remote cache here. buildPlan still intersects approvals
        // with installed candidates and removes protected / never-disable packages.
        val profileApproved = if (trustedProfile) {
            packageReviewFlags.filterValues { asksForReview -> !asksForReview }.keys
        } else {
            emptySet()
        }
        return profileApproved + knownGlobalPackages
    }

    /** Stable, device-scoped preference key; raw hardware/build identity is never used as a key. */
    internal fun cleanupApprovalPreferenceKey(deviceIdentity: String): String {
        val identityParts = deviceIdentity.split("|")
        require(deviceIdentity.length <= 2048 && identityParts.size == 4 &&
            identityParts.none { it.isBlank() || it == "неизвестно" }) {
            "Некорректный идентификатор телевизора"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(deviceIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "cleanup_approved_packages_v1_$digest"
    }

    /**
     * A deliberate one-button maintenance run authorizes the exact pre-approved plan.
     * Review-only packages never enter safePackages; optional cache trimming has its own consent.
     */
    internal fun shouldAutoExecuteApprovedCleanup(safePackages: Set<String>): Boolean =
        safePackages.isNotEmpty()

    /** Only a verified successful cleanup result may advance the combined Launcher flow. */
    internal fun cleanupResultAllowsLauncherInstall(result: String): Boolean {
        if (result.startsWith("Очистка кэша выполнена:")) {
            return result.contains("Повторная оценка ресурсов:") &&
                !result.contains("не удалось сохранить", ignoreCase = true) &&
                !result.contains("не записаны", ignoreCase = true)
        }
        val match = Regex(
            """Отключено:\s*(\d+)\s+из\s+(\d+)\.\s*Ошибок:\s*0(?:\s|$)"""
        ).find(result) ?: return false
        val disabled = match.groupValues[1].toIntOrNull() ?: return false
        val planned = match.groupValues[2].toIntOrNull() ?: return false
        return disabled == planned
    }

    /** The combined flow also requires the initial diagnostic history to be durable. */
    internal fun combinedLauncherFlowAllowsInstall(
        diagnosticHistorySaved: Boolean,
        cleanupResult: String
    ): Boolean = diagnosticHistorySaved && cleanupResultAllowsLauncherInstall(cleanupResult)

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
        // Defense in depth: the planner enforces the engine's core package
        // policy even if a caller accidentally omits a package from its snapshot.
        val protected = eligible.filterTo(mutableSetOf()) { packageName ->
            packageName in protectedPackages ||
                packageName in neverDisablePackages ||
                isProtectedSystemPackage(packageName)
        }
        val profileApproved = eligible intersect profileApprovedPackages
        val safe = (profileApproved - protected).toSortedSet()
        val review = eligible - profileApproved - protected
        val blocked = protected
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
                !isProtectedSystemPackage(packageName) &&
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

    /** Returns the minimum /data free space required before installing an APK. */
    internal fun requiredStorageBytesForApk(apkBytes: Long): Long? {
        if (apkBytes <= 0L || apkBytes > Long.MAX_VALUE / 2L) return null
        return maxOf(200L * 1024L * 1024L, apkBytes * 2L)
    }

    /** Parses df output for /data, tolerating Android/toybox column and spacing variants. */
    internal fun parseDataStorageSnapshot(output: String): DataStorageSnapshot? {
        // Parse from the right: some Android builds use long filesystem names or
        // slightly different headers. The mount point and numeric columns remain stable.
        val rows = output.lineSequence().map { it.trim().split(Regex("""\\s+""")) }
            .filter { it.lastOrNull() == "/data" }.toList()
        // Multiple /data rows are ambiguous; never guess which mount is authoritative.
        if (rows.size != 1) return null
        val columns = rows.single()
        if (columns.size < 5) return null

        val hasPercent = columns.getOrNull(columns.size - 2)?.endsWith("%") == true
        val totalIndex = columns.size - if (hasPercent) 5 else 4
        val totalKb = columns.getOrNull(totalIndex)?.toLongOrNull() ?: return null
        val usedKb = columns.getOrNull(totalIndex + 1)?.toLongOrNull() ?: return null
        val availableKb = columns.getOrNull(totalIndex + 2)?.toLongOrNull() ?: return null
        val usedPercent = if (hasPercent) {
            columns[columns.size - 2].removeSuffix("%").toIntOrNull() ?: return null
        } else {
            if (totalKb <= 0L) return null
            ((usedKb.toDouble() / totalKb.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }

        if (totalKb <= 0 || totalKb > Long.MAX_VALUE / 1024L ||
            usedKb < 0 || availableKb < 0 || usedPercent !in 0..100
        ) return null
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
