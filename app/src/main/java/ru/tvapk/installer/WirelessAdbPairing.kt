package ru.tvapk.installer

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.model.Endpoint
import java.security.MessageDigest

/**
 * Owns Kadb's process-wide Wireless Debugging identity and the per-TV TLS connect endpoint map.
 * The private key is encrypted with an Android Keystore AES key by AndroidKeystoreAdbPrivateKeyStore.
 */
internal object WirelessAdbPairing {
    private const val PREFS = "wireless_adb_pairings"
    private const val DEFAULT_DEVICE_NAME = "Clean Your TV"

    @Volatile private var configured = false
    private val lock = Any()

    fun configure(context: Context) {
        if (configured) return
        synchronized(lock) {
            if (configured) return
            KadbCert.configure(AndroidKeystoreAdbPrivateKeyStore(context.applicationContext))
            configured = true
        }
    }

    suspend fun pair(context: Context, tv: FoundTv, pairingCode: String) {
        require(Regex("^[0-9]{6}$").matches(pairingCode)) {
            "Код сопряжения должен содержать ровно 6 цифр"
        }
        val pairing = tv.pairingService ?: error(
            "Телевизор не объявил порт сопряжения. На ТВ открой «Сопряжение устройства с помощью кода»."
        )
        val connect = tv.connectService ?: error(
            "Не найден TLS-порт подключения. Оставь на телевизоре включённой беспроводную отладку и повтори поиск."
        )
        require(pairing.port in 1..65535 && connect.port in 1..65535) { "Телевизор сообщил неверный ADB-порт" }
        require(tv.host.isNotBlank() && pairing.host.isNotBlank()) { "Не удалось определить адрес телевизора" }

        configure(context)
        KadbCert.ensureReady()
        Kadb.pair(
            host = pairing.host,
            port = pairing.port,
            pairingCode = pairingCode,
            name = DEFAULT_DEVICE_NAME
        )

        // Pairing and TLS connection use two distinct DNS-SD services and ports.
        // Save only after Kadb reports a completed SPAKE2 + peer-certificate exchange.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(hostKey(tv.host), connect.host)
            .putInt(portKey(tv.host), connect.port)
            .putString(serviceKey(tv.host), connect.name)
            .commit()
            .also { check(it) { "Не удалось сохранить сопряжение телевизора" } }
    }

    fun endpointForHost(context: Context, host: String): Endpoint.Tls? {
        if (host.isBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedHost = prefs.getString(hostKey(host), null) ?: return null
        val port = prefs.getInt(portKey(host), -1)
        if (port !in 1..65535 || !sameAddress(host, savedHost)) return null
        return Endpoint.Tls(host, port, prefs.getString(serviceKey(host), "_adb-tls-connect._tcp."))
    }

    fun isPairedEndpoint(context: Context, host: String, port: Int): Boolean =
        endpointForHost(context, host)?.port == port

    private fun sameAddress(a: String, b: String): Boolean =
        a.trim().substringBefore('%').equals(b.trim().substringBefore('%'), ignoreCase = true)

    private fun key(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hostKey(host: String) = "connect_host_" + key(host)
    private fun portKey(host: String) = "connect_port_" + key(host)
    private fun serviceKey(host: String) = "connect_service_" + key(host)
}
