package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkInstallVerifierTest {
    @Test
    fun acceptsMatchingInstalledPackageAndVersion() {
        assertNull(
            ApkInstallVerifier.verify(
                "com.example.app", 42L, setOf("com.example.app"), 42L
            )
        )
    }

    @Test
    fun rejectsPackageMissingAfterInstall() {
        assertEquals(
            "После установки пакет com.example.app не найден на телевизоре",
            ApkInstallVerifier.verify("com.example.app", 42L, emptySet(), null)
        )
    }

    @Test
    fun rejectsVersionMismatch() {
        assertEquals(
            "Версия com.example.app не совпадает: ожидалась 42, установлена 41",
            ApkInstallVerifier.verify("com.example.app", 42L, setOf("com.example.app"), 41L)
        )
    }

    @Test
    fun rejectsInvalidPackageNameAndUnknownVersion() {
        assertEquals(
            "APK содержит некорректное имя пакета",
            ApkInstallVerifier.verify("not a package", 42L, emptySet(), null)
        )
        assertEquals(
            "Не удалось определить версию выбранного APK",
            ApkInstallVerifier.verify("com.example.app", 0L, emptySet(), null)
        )
    }
}
