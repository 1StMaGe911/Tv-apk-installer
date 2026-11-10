package ru.tvapk.installer

internal object UpdateArtifactPolicy {
    const val MIN_APK_BYTES = 1024L
    const val MAX_APK_BYTES = 200L * 1024L * 1024L

    private val allowedDownloadHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    fun isAllowedDownloadUrl(value: String): Boolean {
        if (value.length > 2048) return false
        val uri = try { URI(value) } catch (_: Exception) { return false }
        return uri.isAbsolute && !uri.isOpaque &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.host?.lowercase(Locale.ROOT) in allowedDownloadHosts
    }

    fun isValidSize(size: Long): Boolean = size in MIN_APK_BYTES..MAX_APK_BYTES

    fun matchesSize(actualSize: Long, expectedSize: Long): Boolean =
        isValidSize(expectedSize) && actualSize == expectedSize

    fun matchesSha256(actualSha256: String, expectedSha256: String): Boolean =
        expectedSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            actualSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            actualSha256.equals(expectedSha256, ignoreCase = true)

    fun isTrustedUpdate(
        packageName: String,
        installedPackageName: String,
        archiveSignerSha256: String,
        installedSignerSha256: String,
        archiveVersionCode: Long,
        installedVersionCode: Long
    ): Boolean =
        packageName.isNotBlank() &&
            packageName == installedPackageName &&
            matchesSha256(archiveSignerSha256, installedSignerSha256) &&
            archiveVersionCode > installedVersionCode
}
