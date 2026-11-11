package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseNotesPolicyTest {
    @Test
    fun extractsChangeBulletsAndOmitsBuildMetadata() {
        assertEquals(
            "Добавлена функция • Исправлена ошибка",
            UpdateReleaseNotesPolicy.forNotification(
                "Автоматическая сборка TV APK Installer v0.9.131.\n" +
                    "## 0.9.131 — улучшения\n" +
                    "- Добавлена функция\n" +
                    "- Исправлена ошибка\n\n" +
                    "SHA-256: abc123"
            )
        )
    }

    @Test
    fun returnsEmptyWhenReleaseHasNoHumanReadableNotes() {
        assertEquals(
            "",
            UpdateReleaseNotesPolicy.forNotification(
                "Автоматическая сборка TV APK Installer v0.9.131.\nSHA-256: abc123"
            )
        )
    }

    @Test
    fun limitsNotificationNotesLength() {
        assertTrue(UpdateReleaseNotesPolicy.forNotification("- " + "x".repeat(2_000)).length <= 1_000)
    }
}
