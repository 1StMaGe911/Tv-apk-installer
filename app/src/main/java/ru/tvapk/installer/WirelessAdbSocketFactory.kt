package ru.tvapk.installer

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.wire.AdbSocket
import tv.cleanyour.adb.wire.SocketFactory
import tv.cleanyour.adb.wire.TlsUpgrader
import tv.cleanyour.android.platform.PlainSocketFactory
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.Sha256
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * ADB's TLS upgrade is STARTTLS: the ADB CNXN/STLS frames travel over the initial
 * plain TCP socket. Do not perform TLS in connect(), or adbd never receives CNXN.
 *
 * For a host paired through Wireless Debugging, a normal Setup PlainTcp endpoint
 * is redirected to the saved _adb-tls-connect._tcp port. Other devices retain
 * the existing classic ADB TCP behavior.
 */
internal class WirelessAdbSocketFactory(context: Context) : SocketFactory, TlsUpgrader {
    private val app = context.applicationContext
    private val plain = PlainSocketFactory()

    override suspend fun connect(endpoint: Endpoint, timeoutMs: Long): AdbSocket {
        val target = when (endpoint) {
            is Endpoint.PlainTcp -> WirelessAdbPairing.endpointForHost(app, endpoint.host)
            is Endpoint.Tls -> endpoint.takeIf {
                WirelessAdbPairing.isPairedEndpoint(app, it.host, it.port)
            }
        }
        if (target == null) {
            if (endpoint is Endpoint.PlainTcp) return plain.connect(endpoint, timeoutMs)
            throw AdbException(AdbError.Transport.Protocol("TLS endpoint has not been paired on this phone"))
        }
        if (!PlainSocketFactory.isLiteralAddress(target.host)) {
            throw AdbException(AdbError.Transport.Protocol("Wireless Debugging requires a literal local IP address"))
        }
        return withContext(Dispatchers.IO) {
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.keepAlive = true
                raw.connect(
                    InetSocketAddress(target.host, target.port),
                    timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
                StartTlsAdbSocket(raw, target.host, target.port)
            } catch (error: Exception) {
                runCatching { raw.close() }
                throw error
            }
        }
    }

    override suspend fun upgrade(socket: AdbSocket, pin: Sha256?): AdbSocket {
        val startTls = socket as? StartTlsAdbSocket
            ?: throw AdbException(AdbError.Transport.Protocol("The TV requested TLS on a non-paired ADB socket"))
        val identity = WirelessAdbPairing.ensureIdentity(app)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(decodePem(identity.privateKeyPem, "PRIVATE KEY"))
        )
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(identity.certificatePem)
        ) as X509Certificate

        // ADB uses a non-standard CA issuer name (O=AdbKey-0,CN=<key fingerprint>).
        // The platform's default KeyManagerFactory often refuses that issuer; Kadb uses a
        // custom X509ExtendedKeyManager for this exact ADB TLS client-certificate request.
        val keyManager = WirelessAdbKeyManager(privateKey, certificate)
        // This matches Kadb's ADB TLS policy: the device's ephemeral TLS server
        // certificate is not a Web PKI certificate; client identity is the paired ADB cert.
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }

        return withContext(Dispatchers.IO) {
            var ssl: SSLSocket? = null
            try {
                ssl = sslContext.socketFactory.createSocket(
                    startTls.rawSocket, startTls.host, startTls.port, true
                ) as SSLSocket
                val supported = ssl.supportedProtocols.toSet()
                if ("TLSv1.3" !in supported) {
                    throw IOException("This Android version does not support TLS 1.3 required by Wireless Debugging")
                }
                ssl.enabledProtocols = arrayOf("TLSv1.3")
                ssl.tcpNoDelay = true
                ssl.keepAlive = true
                ssl.startHandshake()
                startTls.detach()
                TlsAdbSocket(ssl)
            } catch (error: Exception) {
                runCatching { ssl?.close() }
                runCatching { startTls.close() }
                throw error
            }
        }
    }

    private fun decodePem(bytes: ByteArray, label: String): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
        return Base64.getMimeDecoder().decode(text)
    }
}

private class WirelessAdbKeyManager(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    private val alias = "wireless-adb"
    private fun supportsRsa(keyType: String?): Boolean =
        keyType == null || keyType.contains("RSA", ignoreCase = true)

    private fun choose(keyTypes: Array<out String>?): String? =
        if (keyTypes.isNullOrEmpty() || keyTypes.any(::supportsRsa)) alias else null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (supportsRsa(keyType)) arrayOf(alias) else emptyArray()

    override fun chooseClientAlias(
        keyTypes: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = choose(keyTypes)

    override fun chooseEngineClientAlias(
        keyTypes: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = choose(keyTypes)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) arrayOf(certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) privateKey else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

private open class SocketBackedAdbSocket(protected val socket: Socket) : AdbSocket {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = withContext(Dispatchers.IO) {
        try {
            input.read(buf, off, len)
        } catch (error: IOException) {
            if (socket.isClosed) -1 else throw error
        }
    }

    override suspend fun write(buf: ByteArray, off: Int, len: Int) = withContext(Dispatchers.IO) {
        output.write(buf, off, len)
    }

    override suspend fun flush() = withContext(Dispatchers.IO) { output.flush() }

    override fun close() {
        try {
            socket.close()
        } catch (error: IOException) {
            // Closing is best-effort; it must also unblock an in-flight read.
        } catch (error: CancellationException) {
            throw error
        }
    }
}

private class StartTlsAdbSocket(
    val rawSocket: Socket,
    val host: String,
    val port: Int
) : SocketBackedAdbSocket(rawSocket) {
    private var detached = false
    fun detach() { detached = true }
    override fun close() {
        if (!detached) super.close()
    }
}

private class TlsAdbSocket(socket: SSLSocket) : SocketBackedAdbSocket(socket)
