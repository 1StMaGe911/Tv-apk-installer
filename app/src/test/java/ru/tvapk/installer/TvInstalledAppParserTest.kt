package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvInstalledAppParserTest {
    @Test
    fun parsesVersionAndSumsSplitApkSizes() {
        val app = parseInstalledApp(
            "com.example.player",
            "Package [com.example.player] (abc):\n    versionCode=42 minSdk=23 targetSdk=35\n    versionName=2.5.1",
            "128 /data/app/pkg/base.apk\n64 /data/app/pkg/split_config.arm64.apk\n"
        )

        assertEquals("com.example.player", app.packageName)
        assertEquals("2.5.1", app.versionName)
        assertEquals("42", app.versionCode)
        assertEquals(192L * 1024L, app.apkSizeBytes)
    }

    @Test
    fun missingMetadataDoesNotBreakTheAppList() {
        val app = parseInstalledApp("com.example.unknown", "", "")

        assertNull(app.versionName)
        assertNull(app.versionCode)
        assertNull(app.apkSizeBytes)
    }
}
