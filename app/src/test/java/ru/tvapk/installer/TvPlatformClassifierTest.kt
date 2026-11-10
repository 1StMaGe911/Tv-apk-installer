package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlatformClassifierTest {
    @Test
    fun detectsGoogleTvByItsHomePackageRegardlessOfManufacturer() {
        val signals = TvPlatformSignals(
            manufacturer = "Sony",
            brand = "BRAVIA",
            installedPackages = setOf("com.google.android.apps.tv.launcherx")
        )

        assertEquals(TvPlatformKind.GOOGLE_TV, TvPlatformClassifier.classify(signals))
    }

    @Test
    fun detectsAndroidTvByLeanbackFeatureAcrossOemBrands() {
        val signals = TvPlatformSignals(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            systemFeatures = setOf("android.software.leanback")
        )

        assertEquals(TvPlatformKind.ANDROID_TV, TvPlatformClassifier.classify(signals))
    }

    @Test
    fun detectsAndroidTvByTvBuildCharacteristic() {
        val signals = TvPlatformSignals(
            manufacturer = "Philips",
            buildCharacteristics = "default,tv"
        )

        assertEquals(TvPlatformKind.ANDROID_TV, TvPlatformClassifier.classify(signals))
    }

    @Test
    fun detectsAndroidTvByKnownTvLauncherPackage() {
        val signals = TvPlatformSignals(
            manufacturer = "Unknown OEM",
            installedPackages = setOf("com.google.android.tvlauncher")
        )

        assertEquals(TvPlatformKind.ANDROID_TV, TvPlatformClassifier.classify(signals))
    }

    @Test
    fun detectsFireTvBeforeGenericTvSignals() {
        val signals = TvPlatformSignals(
            manufacturer = "Amazon",
            buildCharacteristics = "tv",
            installedPackages = setOf("com.google.android.tvlauncher")
        )

        assertEquals(TvPlatformKind.FIRE_TV, TvPlatformClassifier.classify(signals))
    }

    @Test
    fun doesNotAssumeEveryAndroidDeviceIsAnAndroidTv() {
        val signals = TvPlatformSignals(
            manufacturer = "Samsung",
            brand = "Samsung",
            model = "Galaxy"
        )

        assertEquals(TvPlatformKind.UNKNOWN, TvPlatformClassifier.classify(signals))
    }
}
