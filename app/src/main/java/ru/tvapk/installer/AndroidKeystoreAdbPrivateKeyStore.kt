package ru.tvapk.installer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small boundaries keep persistence and key custody independently testable on the JVM.
 * Production implementations below still use app-private SharedPreferences and Android Keystore.
 */
internal interface AdbIdentityPreferences {
    fun getString(key: String): String?
    fun writeEncryptedIdentity(encryptedKey: String, initializationVector: String): Boolean
    fun clearIdentity(): Boolean
}

internal interface AdbIdentitySecretKeyProvider {
    fun getExistingSecretKey(): SecretKey
    fun getOrCreateSecretKey(): SecretKey
}

internal class AndroidKeystoreAdbPrivateKeyStore internal constructor(
    private val preferences: AdbIdentityPreferences,
    private val secretKeys: AdbIdentitySecretKeyProvider
) : KadbPrivateKeyStore {

    constructor(context: Context) : this(
        SharedPreferencesAdbIdentityPreferences(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        ),
        AndroidKeystoreAdbIdentitySecretKeyProvider()
    )

    @Synchronized
    override fun readPrivateKeyPem(): ByteArray? {
        val encoded = preferences.getString(ENCRYPTED_KEY)
        val iv = preferences.getString(INITIALIZATION_VECTOR)
        if (encoded == null && iv == null) return null
        check(encoded != null && iv != null) {
            "Saved ADB identity is incomplete; refusing to generate a new identity"
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKeys.getExistingSecretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.getDecoder().decode(iv))
            )
            cipher.doFinal(Base64.getDecoder().decode(encoded))
        } catch (error: Exception) {
            throw IllegalStateException("Unable to decrypt the saved ADB identity", error)
        }
    }

    @Synchronized
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        require(privateKeyPem.isNotEmpty()) { "ADB private key must not be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeys.getOrCreateSecretKey())
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(privateKeyPem))
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        check(preferences.writeEncryptedIdentity(encrypted, iv)) {
            "Unable to persist the encrypted ADB identity"
        }
    }

    @Synchronized
    override fun clear() {
        check(preferences.clearIdentity()) { "Unable to clear the saved ADB identity" }
    }

    private companion object {
        const val PREFERENCES_NAME = "wireless_adb_identity"
        const val ENCRYPTED_KEY = "encrypted_private_key"
        const val INITIALIZATION_VECTOR = "private_key_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}

private class SharedPreferencesAdbIdentityPreferences(
    private val preferences: android.content.SharedPreferences
) : AdbIdentityPreferences {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun writeEncryptedIdentity(encryptedKey: String, initializationVector: String): Boolean =
        preferences.edit()
            .putString("encrypted_private_key", encryptedKey)
            .putString("private_key_iv", initializationVector)
            .commit()

    override fun clearIdentity(): Boolean =
        preferences.edit()
            .remove("encrypted_private_key")
            .remove("private_key_iv")
            .commit()
}

private class AndroidKeystoreAdbIdentitySecretKeyProvider : AdbIdentitySecretKeyProvider {
    override fun getExistingSecretKey(): SecretKey =
        loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Android Keystore key for the saved ADB identity is missing")

    override fun getOrCreateSecretKey(): SecretKey {
        (loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "tv_apk_installer_adb_identity"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALGORITHM = "AES"
    }
}
