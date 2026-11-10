package ru.tvapk.installer

/**
 * Signals read from the connected device through read-only ADB queries.
 *
 * Classification intentionally relies on platform markers, not manufacturer names:
 * Android TV and Google TV are shipped by many OEMs and custom firmware vendors.
 */
internal data class TvPlatformSignals(
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val buildCharacteristics: String = "",
    val installedPackages: Set<String> = emptySet(),
    val systemFeatures: Set<String> = emptySet()
)

internal enum class TvPlatformKind {
    GOOGLE_TV,
    ANDROID_TV,
    FIRE_TV,
    UNKNOWN
}

internal object TvPlatformClassifier {
    private const val GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx"
    private val androidTvHomePackages = setOf(
        "com.google.android.tvlauncher",
        "com.android.tv.launcher"
    )
    private const val LEANBACK_FEATURE = "android.software.leanback"

    fun classify(signals: TvPlatformSignals): TvPlatformKind {
        val amazonDevice = signals.manufacturer.equals("Amazon", ignoreCase = true) ||
            signals.brand.equals("Amazon", ignoreCase = true) ||
            signals.model.startsWith("AFT", ignoreCase = true) ||
            signals.installedPackages.any {
                it == "com.amazon.tv.launcher" || it == "com.amazon.firehomestarter"
            }
        if (amazonDevice) return TvPlatformKind.FIRE_TV

        if (GOOGLE_TV_HOME in signals.installedPackages) {
            return TvPlatformKind.GOOGLE_TV
        }

        val isTvBuild = signals.buildCharacteristics
            .split(',', ' ', ';')
            .any { it.equals("tv", ignoreCase = true) }
        val hasLeanback = signals.systemFeatures.any {
            it.equals(LEANBACK_FEATURE, ignoreCase = true)
        }
        val hasAndroidTvHome = signals.installedPackages.any {
            it in androidTvHomePackages
        }
        return if (isTvBuild || hasLeanback || hasAndroidTvHome) {
            TvPlatformKind.ANDROID_TV
        } else {
            TvPlatformKind.UNKNOWN
        }
    }
}
