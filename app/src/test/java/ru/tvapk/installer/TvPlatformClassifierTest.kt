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
    fun detectsAndroidTvByTelevisionHardwareFeatureAcrossOemBrands() {
        val signals = TvPlatformSignals(
            manufacturer = "Skyworth",
            brand = "Coocaa",
            systemFeatures = setOf("android.hardware.type.television")
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

    @Test
    fun profileLabelMatchesOemNamesAsWholeTokens() {
        assertEquals("LG • Android TV / Android", TvPlatformClassifier.profileLabel("manufacturer=LG; model=OLED"))
        assertEquals("Универсальный профиль • Android TV / Android", TvPlatformClassifier.profileLabel(
            "model=Google TV; package=com.google.android.apps.tv.launcherx"
        ))
        assertEquals("TCL • Android TV / Android", TvPlatformClassifier.profileLabel("brand=TCL; build=tv"))
        assertEquals("Xiaomi • Android TV / Android", TvPlatformClassifier.profileLabel("manufacturer=Redmi"))
    }

    @Test
    fun profileLabelDoesNotMatchShortOemNamesInsideUnrelatedWords() {
        assertEquals("Универсальный профиль • Android TV / Android", TvPlatformClassifier.profileLabel(
            "manufacturer=Google; model=Building-2026; build=android"
        ))
        assertEquals("Универсальный профиль • Android-совместимое устройство",
            TvPlatformClassifier.profileLabel("manufacturer=Unknown; model=Galaxy"))
    }

    @Test
    fun profileLabelRecognizesFireTvAndVidaa() {
        assertEquals("Amazon Fire TV • Fire TV / Fire OS",
            TvPlatformClassifier.profileLabel("manufacturer=Amazon; model=AFTMM"))
        assertEquals("Универсальный профиль • VIDAA (не Android)",
            TvPlatformClassifier.profileLabel("platform=VIDAA"))
    }

}
