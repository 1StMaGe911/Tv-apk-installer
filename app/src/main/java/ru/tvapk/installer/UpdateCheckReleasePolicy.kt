package ru.tvapk.installer

/** Validates and normalizes a GitHub release tag to the app's numeric version format. */
internal object UpdateCheckReleasePolicy {
    private val versionPattern = Regex("^v?\\d+(?:\\.\\d+){1,3}$")

    fun normalizeTag(rawTag: String): String? {
        if (!versionPattern.matches(rawTag)) return null
        return rawTag.removePrefix("v")
    }
}
