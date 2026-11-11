package ru.tvapk.installer

import android.content.Context
import android.util.Base64
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the ADB host private key encrypted with an Android Keystore AES key.
 *
 * The encrypted payload is kept in app-private SharedPreferences. The AES key is
 * non-exportable and scoped to this application; the plaintext ADB key is never
 * written to disk by this store.
 */
internal class AndroidKeystoreAdbPrivateKeyStore(context: Context) : KadbPrivateKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun readPrivateKeyPem(): ByteArray? {
        val encoded = preferences.getString(ENCRYPTED_KEY, null)
        val iv = preferences.getString(INITIALIZATION_VECTOR, null)
        if (encoded == null && iv == null) return null
        check(encoded != null && iv != null) {
            "Saved ADB identity is incomplete; refusing to generate a new identity"
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        }.getOrElse { error ->
            throw IllegalStateException("Unable to decrypt the saved ADB identity", error)
        }
    }

    @Synchronized
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        require(privateKeyPem.isNotEmpty()) { "ADB private key must not be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = Base64.encodeToString(cipher.doFinal(privateKeyPem), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        check(
            preferences.edit()
                .putString(ENCRYPTED_KEY, encrypted)
                .putString(INITIALIZATION_VECTOR, iv)
                .commit()
        ) { "Unable to persist the encrypted ADB identity" }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove(ENCRYPTED_KEY).remove(INITIALIZATION_VECTOR).commit()) {
            "Unable to clear the saved ADB identity"
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "wireless_adb_identity"
        const val ENCRYPTED_KEY = "encrypted_private_key"
        const val INITIALIZATION_VECTOR = "private_key_iv"
        const val KEY_ALIAS = "tv_apk_installer_adb_identity"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
