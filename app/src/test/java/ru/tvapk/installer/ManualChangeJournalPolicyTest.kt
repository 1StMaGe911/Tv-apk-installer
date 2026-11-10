package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualChangeJournalPolicyTest {
    @Test
    fun acceptsValidUniqueEntries() {
        val result = ManualChangeJournalPolicy.validateEntries(
            entries = listOf("device-a|com.example.one", "device-b|com.example.two"),
            key = { it },
            isValid = { "|" in it }
        )

        assertEquals(2, result.size)
    }

    @Test
    fun rejectsMalformedEntriesInsteadOfDroppingThem() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualChangeJournalPolicy.validateEntries(
                entries = listOf("device-a|com.example.one", "malformed"),
                key = { it },
                isValid = { "|" in it }
            )
        }
    }

    @Test
    fun rejectsDuplicateEntriesInsteadOfSilentlyKeepingOne() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualChangeJournalPolicy.validateEntries(
                entries = listOf("device-a|com.example.one", "device-a|com.example.one"),
                key = { it },
                isValid = { "|" in it }
            )
        }
    }

    @Test
    fun rejectsBlankKeysAndOversizedJournals() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualChangeJournalPolicy.validateEntries(
                entries = listOf("valid"),
                key = { "" },
                isValid = { true }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualChangeJournalPolicy.validateEntries(
                entries = listOf("one", "two"),
                key = { it },
                isValid = { true },
                maxEntries = 1
            )
        }
    }
}
