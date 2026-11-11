package ru.tvapk.installer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryApkPreservationTest {
    @Test
    fun preservationMovesAndVerifiesEveryExpectedApk() {
        val root = Files.createTempDirectory("recovery-apk-test").toFile()
        try {
            val source = File(root, "rollback").apply { mkdirs() }
            val destination = File(root, "cleanyourtv-recovery-123")
            val launcher = byteArrayOf(1, 2, 3, 4, 5)
            val curtain = byteArrayOf(9, 8, 7, 6)
            val launcherFile = File(source, "launcher.apk").apply { writeBytes(launcher) }
            val curtainFile = File(source, "curtain.apk").apply { writeBytes(curtain) }

            assertTrue(preserveRecoveryApks(source, destination, listOf(launcherFile, curtainFile)))
            assertFalse(source.exists())
            assertArrayEquals(launcher, File(destination, "launcher.apk").readBytes())
            assertArrayEquals(curtain, File(destination, "curtain.apk").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservationRejectsEmptyBackupSetAndExistingDestination() {
        val root = Files.createTempDirectory("recovery-apk-test").toFile()
        try {
            val source = File(root, "rollback").apply { mkdirs() }
            val destination = File(root, "cleanyourtv-recovery-123").apply { mkdirs() }
            val backup = File(source, "launcher.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            assertFalse(preserveRecoveryApks(source, destination, emptyList()))
            assertFalse(preserveRecoveryApks(source, destination, listOf(backup)))
            assertTrue(backup.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservationRejectsBackupOutsideRollbackDirectory() {
        val root = Files.createTempDirectory("recovery-apk-test").toFile()
        try {
            val source = File(root, "rollback").apply { mkdirs() }
            val outside = File(root, "outside.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val destination = File(root, "cleanyourtv-recovery-123")

            assertFalse(preserveRecoveryApks(source, destination, listOf(outside)))
            assertFalse(destination.exists())
            assertTrue(outside.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
