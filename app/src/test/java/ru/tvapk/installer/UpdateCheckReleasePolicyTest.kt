package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckReleasePolicyTest {
    @Test
    fun normalizesSupportedNumericReleaseTags() {
        assertEquals("0.9.131", UpdateCheckReleasePolicy.normalizeTag("v0.9.131"))
        assertEquals("1.2", UpdateCheckReleasePolicy.normalizeTag("1.2"))
        assertEquals("2.10.4.7", UpdateCheckReleasePolicy.normalizeTag("v2.10.4.7"))
    }

    @Test
    fun rejectsEmptyMalformedAndNonNumericReleaseTags() {
        listOf(
            "",
            "latest",
            "release-1.2.3",
            "v",
            "1",
            "1.2.3.4.5",
            "1.2-beta",
            "v1..2",
            " 1.2.3",
            "1.2.3 "
        ).forEach { tag ->
            assertNull("Tag '$tag' must be rejected", UpdateCheckReleasePolicy.normalizeTag(tag))
        }
    }
}
