package ru.tvapk.installer

import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeystoreAdbPrivateKeyStoreTest {

    @Test
    fun emptyStoreHasNoIdentity() {
        val store = newStore()
        assertNull(store.readPrivateKeyPem())
    }

    @Test
    fun writeEncryptsAndReadRestoresTheOriginalIdentity() {
        val preferences = InMemoryPreferences()
        val store = newStore(preferences)
        val privateKey = "-----BEGIN PRIVATE KEY-----\nprivate material\n-----END PRIVATE KEY-----"
            .toByteArray(StandardCharsets.UTF_8)

        store.writePrivateKeyPemAtomic(privateKey)

        val persisted = preferences.values["encrypted_private_key"]
        assertTrue(persisted != null)
        assertFalse(persisted!!.contains("private material"))
        assertArrayEquals(privateKey, store.readPrivateKeyPem())
    }

    @Test
    fun emptyIdentityIsRejectedBeforePersistence() {
        val preferences = InMemoryPreferences()
        val store = newStore(preferences)

        assertThrows(IllegalArgumentException::class.java) {
            store.writePrivateKeyPemAtomic(ByteArray(0))
        }
        assertTrue(preferences.values.isEmpty())
    }

    @Test
    fun partialIdentityIsRejectedInsteadOfSilentlyReplacingIt() {
        val preferences = InMemoryPreferences().apply {
            values["encrypted_private_key"] = "partial"
        }
        val store = newStore(preferences)

        assertThrows(IllegalStateException::class.java) {
            store.readPrivateKeyPem()
        }
    }

    @Test
    fun missingEncryptionKeyDoesNotCreateANewIdentityDuringRead() {
        val preferences = InMemoryPreferences()
        val store = newStore(preferences)
        store.writePrivateKeyPemAtomic("private-key".toByteArray(StandardCharsets.UTF_8))
        val readWithoutKey = AndroidKeystoreAdbPrivateKeyStore(
            preferences,
            InMemorySecretKeyProvider().apply { available = false }
        )

        assertThrows(IllegalStateException::class.java) {
            readWithoutKey.readPrivateKeyPem()
        }
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val preferences = InMemoryPreferences()
        val store = newStore(preferences)
        store.writePrivateKeyPemAtomic("private-key".toByteArray(StandardCharsets.UTF_8))
        val encrypted = preferences.values.getValue("encrypted_private_key")
        preferences.values["encrypted_private_key"] = encrypted.dropLast(2) + "AA"

        assertThrows(IllegalStateException::class.java) {
            store.readPrivateKeyPem()
        }
    }

    @Test
    fun clearRemovesBothEncryptedPayloadAndIv() {
        val preferences = InMemoryPreferences()
        val store = newStore(preferences)
        store.writePrivateKeyPemAtomic("private-key".toByteArray(StandardCharsets.UTF_8))

        store.clear()

        assertTrue(preferences.values.isEmpty())
        assertNull(store.readPrivateKeyPem())
    }

    @Test
    fun failedAtomicWriteIsReported() {
        val preferences = InMemoryPreferences().apply { writeSucceeds = false }
        val store = newStore(preferences)

        assertThrows(IllegalStateException::class.java) {
            store.writePrivateKeyPemAtomic("private-key".toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun newStore(
        preferences: InMemoryPreferences = InMemoryPreferences()
    ) = AndroidKeystoreAdbPrivateKeyStore(preferences, InMemorySecretKeyProvider())

    private class InMemoryPreferences : AdbIdentityPreferences {
        val values = mutableMapOf<String, String>()
        var writeSucceeds = true

        override fun getString(key: String): String? = values[key]

        override fun writeEncryptedIdentity(encryptedKey: String, initializationVector: String): Boolean {
            if (!writeSucceeds) return false
            values["encrypted_private_key"] = encryptedKey
            values["private_key_iv"] = initializationVector
            return true
        }

        override fun clearIdentity(): Boolean {
            values.remove("encrypted_private_key")
            values.remove("private_key_iv")
            return true
        }
    }

    private class InMemorySecretKeyProvider : AdbIdentitySecretKeyProvider {
        var available = true
        private val key: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun getExistingSecretKey(): SecretKey {
            check(available) { "Test key is missing" }
            return key
        }

        override fun getOrCreateSecretKey(): SecretKey = key
    }
}
