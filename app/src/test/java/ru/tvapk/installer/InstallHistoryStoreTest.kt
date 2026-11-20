package ru.tvapk.installer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InstallHistoryStoreTest {
    @Test
    fun recordsNewestEntriesAndPersistsThem() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        InstallHistoryStore.clear(context)

        val older = InstallHistoryEntry(
            id = "older",
            timestamp = 100L,
            apkName = "old.apk",
            packageName = "com.example.old",
            tvName = "Living room TV",
            connection = "Wi-Fi",
            success = true,
            error = null
        )
        val newer = InstallHistoryEntry(
            id = "newer",
            timestamp = 200L,
            apkName = "broken.apk",
            packageName = null,
            tvName = "Bedroom TV",
            connection = "USB OTG",
            success = false,
            error = "INSTALL_FAILED"
        )

        InstallHistoryStore.record(context, older)
        InstallHistoryStore.record(context, newer)

        assertEquals(listOf(newer, older), InstallHistoryStore.load(context))
        InstallHistoryStore.clear(context)
        assertTrue(InstallHistoryStore.load(context).isEmpty())
    }
}
