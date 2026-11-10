package ru.tvapk.installer

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import dadb.Dadb
import java.io.IOException

/**
 * Owns Kadb's process-wide certificate identity configuration and exposes its
 * TLS transport through the same Dadb boundary used by maintenance operations.
 *
 * Pairing and TLS-connect endpoints are deliberately separate: Android Wireless
 * Debugging advertises different DNS-SD services and ports for the two phases.
 */
internal object KadbTlsTransport {
    private val configurationLock = Any()

    @Volatile
    private var identityConfigured = false

    /**
     * Configure once per process. KadbCert is a process-wide singleton; calling
     * configure for every connection would unnecessarily invalidate its in-memory
     * identity snapshot while other operations may be using it.
     */
    fun configureIdentity(context: Context) {
        if (identityConfigured) return
        synchronized(configurationLock) {
            if (identityConfigured) return
            KadbCert.configure(
                store = AndroidKeystoreAdbPrivateKeyStore(context.applicationContext)
            )
            identityConfigured = true
        }
    }

    /**
     * Complete Android 11+ Wireless Debugging pairing on the pairing-service port.
     * Call from a worker thread; Kadb performs the network exchange asynchronously.
     */
    suspend fun pair(
        context: Context,
        host: String,
        pairingPort: Int,
        pairingCode: String,
        deviceName: String
    ) {
        validateEndpoint(host, pairingPort)
        require(PAIRING_CODE.matches(pairingCode)) {
            "Код сопряжения должен содержать ровно 6 цифр"
        }
        configureIdentity(context)
        Kadb.pair(
            host = host.trim(),
            port = pairingPort,
            pairingCode = pairingCode,
            name = deviceName.trim().ifBlank { DEFAULT_DEVICE_NAME }.take(MAX_DEVICE_NAME_LENGTH)
        )
    }

    /**
     * Create a lazy TLS ADB connection on the separate TLS-connect-service port.
     * The returned Dadb adapter keeps all existing maintenance commands unchanged.
     */
    fun connect(
        context: Context,
        host: String,
        connectPort: Int
    ): Dadb {
        validateEndpoint(host, connectPort)
        configureIdentity(context)
        val connected = Kadb.tryConnection(host.trim(), connectPort)
            ?: throw IOException("TLS ADB handshake failed for $host:$connectPort")
        return KadbDadbAdapter(connected)
    }

    private fun validateEndpoint(host: String, port: Int) {
        require(host.isNotBlank()) { "Не указан адрес телевизора" }
        require(port in 1..65535) { "Порт ADB должен быть в диапазоне 1–65535" }
    }

    private const val DEFAULT_DEVICE_NAME = "TV APK Installer"
    private const val MAX_DEVICE_NAME_LENGTH = 64
    private val PAIRING_CODE = Regex("^[0-9]{6}$")
}
