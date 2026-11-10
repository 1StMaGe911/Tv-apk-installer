package ru.tvapk.installer

internal object UpdateArtifactPolicy {
    const val MIN_APK_BYTES = 1024L
    const val MAX_APK_BYTES = 200L * 1024L * 1024L

    fun isValidSize(size: Long): Boolean = size in MIN_APK_BYTES..MAX_APK_BYTES

    fun matchesSize(actualSize: Long, expectedSize: Long): Boolean =
        isValidSize(expectedSize) && actualSize == expectedSize
}
