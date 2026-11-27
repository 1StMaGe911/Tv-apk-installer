package ru.tvapk.installer

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbIdentitySnapshot
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.model.Endpoint
import java.security.MessageDigest
import java.net.InetAddress

/**
 * Owns Kadb's process-wide Wireless Debugging identity and the per-TV TLS connect endpoint map.
 * The private key is encrypted with an Android Keystore AES key by AndroidKeystoreAdbPrivateKeyStore.
 */
internal object WirelessAdbPairing {
    private const val PREFS = "wireless_adb_pairings"
    private const val DEFAULT_DEVICE_NAME = "Clean Your TV"

    @Volatile private var configured = false
    @Volatile private var identityReady = false
    private var identityStore: AndroidKeystoreAdbPrivateKeyStore? = null
    private val lock = Any()

    fun configure(context: Context) {
        if (configured) return
        synchronized(lock) {
            if (configured) return
            val store = AndroidKeystoreAdbPrivateKeyStore(context.applicationContext)
            identityStore = store
            KadbCert.configure(store)
            configured = true
        }
    }

    fun ensureIdentity(context: Context): KadbIdentitySnapshot {
        configure(context)
        synchronized(lock) {
            if (!identityReady) {
                val store = checkNotNull(identityStore)
                val privateKey = store.readPrivateKeyPem()
                val certificate = store.readCertificatePem()
                check((privateKey == null) == (certificate == null)) {
                    "Saved Wireless Debugging identity is incomplete; refusing to replace it"
                }
                val snapshot = if (privateKey != null && certificate != null) {
                    KadbCert.importIdentity(privateKey, certificate)
                } else {
                    KadbCert.ensureReady()
                }
                // Kadb's default certificate is regenerated from the private key after process death.
                // Persist the certificate too so the TV sees the exact same TLS client identity.
                store.writeCertificatePemAtomic(snapshot.certificatePem)
                identityReady = true
            }
            return KadbCert.exportIdentityOrNull()
                ?: throw IllegalStateException("ADB pairing identity is unavailable")
        }
    }

    suspend fun pair(context: Context, tv: FoundTv, pairingCode: String, connectPortOverride: Int? = null) {
        require(Regex("^[0-9]{6}$").matches(pairingCode)) {
            "Код сопряжения должен содержать ровно 6 цифр"
        }
        val pairing = tv.pairingService ?: error(
            "Телевизор не объявил порт сопряжения. На ТВ открой «Сопряжение устройства с помощью кода»."
        )
        val connect = tv.connectService
        val connectPort = connectPortOverride ?: connect?.port
            ?: error("Укажи порт подключения, который показывает телевизор")
        require(pairing.port in 1..65535 && connectPort in 1..65535) { "Порт должен быть от 1 до 65535" }
        require(tv.host.isNotBlank() && pairing.host.isNotBlank()) { "Не удалось определить адрес телевизора" }
        val connectHost = connect?.host ?: tv.host
        require(sameAddress(tv.host, connectHost)) { "Адрес TLS-подключения не совпадает с адресом телевизора" }

        ensureIdentity(context)
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
            .putString(hostKey(tv.host), connectHost)
            .putInt(portKey(tv.host), connectPort)
            .putString(serviceKey(tv.host), connect?.name ?: "_adb-tls-connect._tcp.")
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

    fun endpointForTv(context: Context, tv: FoundTv): Endpoint.Tls? {
        val saved = endpointForHost(context, tv.host) ?: return null
        val live = tv.connectService ?: return saved
        if (live.port !in 1..65535 || !sameAddress(tv.host, live.host)) return saved
        // Wireless Debugging can publish a new TLS port after a TV reboot. Refresh it from mDNS.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(hostKey(tv.host), live.host)
            .putInt(portKey(tv.host), live.port)
            .putString(serviceKey(tv.host), live.name)
            .apply()
        return Endpoint.Tls(tv.host, live.port, live.name)
    }

    fun isPairedEndpoint(context: Context, host: String, port: Int): Boolean =
        endpointForHost(context, host)?.port == port

    private fun sameAddress(a: String, b: String): Boolean {
        val first = a.trim().substringBefore('%')
        val second = b.trim().substringBefore('%')
        if (!tv.cleanyour.android.platform.PlainSocketFactory.isLiteralAddress(first) ||
            !tv.cleanyour.android.platform.PlainSocketFactory.isLiteralAddress(second)
        ) return false
        return runCatching {
            InetAddress.getByName(first).address.contentEquals(InetAddress.getByName(second).address)
        }.getOrDefault(false)
    }

    private fun key(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hostKey(host: String) = "connect_host_" + key(host)
    private fun portKey(host: String) = "connect_port_" + key(host)
    private fun serviceKey(host: String) = "connect_service_" + key(host)
}
