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
    private const val TELEVISION_HARDWARE_FEATURE = "android.hardware.type.television"

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
        val hasTelevisionHardware = signals.systemFeatures.any {
            it.equals(TELEVISION_HARDWARE_FEATURE, ignoreCase = true)
        }
        val hasAndroidTvHome = signals.installedPackages.any {
            it in androidTvHomePackages
        }
        return if (isTvBuild || hasLeanback || hasTelevisionHardware || hasAndroidTvHome) {
            TvPlatformKind.ANDROID_TV
        } else {
            TvPlatformKind.UNKNOWN
        }
    }
    /**
     * Builds the human-readable profile label from diagnostic text.
     * Match OEM names as tokens so short names cannot match unrelated strings.
     */
    fun profileLabel(details: String): String {
        val value = details.lowercase(java.util.Locale.ROOT)
        fun containsToken(token: String): Boolean =
            Regex("""(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])""")
                .containsMatchIn(value)

        val brand = when {
            containsToken("tcl") -> "TCL"
            containsToken("hisense") -> "Hisense"
            containsToken("xiaomi") || containsToken("redmi") -> "Xiaomi"
            containsToken("sony") -> "Sony"
            containsToken("philips") -> "Philips"
            containsToken("amazon") || containsToken("fire") -> "Amazon Fire TV"
            containsToken("samsung") -> "Samsung"
            containsToken("lg") || containsToken("lge") -> "LG"
            else -> "Универсальный профиль"
        }
        val platform = when {
            containsToken("fire") || containsToken("amazon") -> "Fire TV / Fire OS"
            value.contains("com.google.android.apps.tv.launcherx") || containsToken("google tv") ->
                "Google TV / Android TV"
            containsToken("android") || containsToken("tv") ||
                value.contains("android.software.leanback") ->
                "Android TV / Android"
            containsToken("vidaa") -> "VIDAA (не Android)"
            else -> "Android-совместимое устройство"
        }
        return "$brand • $platform"
    }

}
