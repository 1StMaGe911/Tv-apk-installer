package ru.tvapk.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateArtifactPolicyTest {
    @Test
    fun acceptsOnlyBoundedNonTrivialApkSizes() {
        assertFalse(UpdateArtifactPolicy.isValidSize(0L))
        assertFalse(UpdateArtifactPolicy.isValidSize(1023L))
        assertTrue(UpdateArtifactPolicy.isValidSize(1024L))
        assertTrue(UpdateArtifactPolicy.isValidSize(UpdateArtifactPolicy.MAX_APK_BYTES))
        assertFalse(UpdateArtifactPolicy.isValidSize(UpdateArtifactPolicy.MAX_APK_BYTES + 1L))
    }

    @Test
    fun matchesSha256WithoutCaseSensitivityAndRejectsMalformedHashes() {
        val hash = "a".repeat(64)
        assertTrue(UpdateArtifactPolicy.matchesSha256(hash, hash.uppercase()))
        assertFalse(UpdateArtifactPolicy.matchesSha256("b".repeat(64), hash))
        assertFalse(UpdateArtifactPolicy.matchesSha256(hash, "not-a-hash"))
        assertFalse(UpdateArtifactPolicy.matchesSha256("short", hash))
    }

    @Test
    fun requiresActualDownloadSizeToMatchReleaseMetadata() {
        assertTrue(UpdateArtifactPolicy.matchesSize(4096L, 4096L))
        assertFalse(UpdateArtifactPolicy.matchesSize(4095L, 4096L))
        assertFalse(UpdateArtifactPolicy.matchesSize(4097L, 4096L))
        assertFalse(UpdateArtifactPolicy.matchesSize(4096L, 0L))
        assertFalse(UpdateArtifactPolicy.matchesSize(4096L, UpdateArtifactPolicy.MAX_APK_BYTES + 1L))
    }
}
