package ru.tvapk.installer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.wire.AdbSocket
import tv.cleanyour.adb.wire.SocketFactory
import tv.cleanyour.android.platform.PlainSocketFactory
import tv.cleanyour.android.platform.TcpAdbSocket
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.Endpoint
import com.flyfishxu.kadb.cert.KadbCert
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Plain ADB remains unchanged. A TLS socket is opened only for an exact host/port
 * previously paired through the in-app Wireless Debugging flow.
 */
internal class WirelessAdbSocketFactory(private val context: Context) : SocketFactory {
    private val plain = PlainSocketFactory()

    override suspend fun connect(endpoint: Endpoint, timeoutMs: Long): AdbSocket {
        if (endpoint is Endpoint.PlainTcp) return plain.connect(endpoint, timeoutMs)
        if (endpoint !is Endpoint.Tls) {
            throw AdbException(AdbError.Transport.Protocol("Unsupported ADB endpoint"))
        }
        if (!WirelessAdbPairing.isPairedEndpoint(context, endpoint.host, endpoint.port)) {
            throw AdbException(AdbError.Transport.Protocol("This TLS endpoint has not been paired on this phone"))
        }

        WirelessAdbPairing.configure(context)
        val identity = KadbCert.ensureReady()
        val privateKey = parsePrivateKey(identity.privateKeyPem)
        val certificate = parseCertificate(identity.certificatePem)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("wireless-adb", privateKey, CharArray(0), arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CharArray(0))
        }
        // Android Wireless Debugging authenticates the host with its paired client certificate.
        // The TV's short-lived self-signed server certificate is not a public PKI identity.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(keyManagers.keyManagers, arrayOf(trust), SecureRandom())
        }

        return withContext(Dispatchers.IO) {
            val socket = sslContext.socketFactory.createSocket() as javax.net.ssl.SSLSocket
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.startHandshake()
                TcpAdbSocket(socket)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }
    }

    private fun parsePrivateKey(pem: ByteArray) =
        KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(
                Base64.getMimeDecoder().decode(
                    pem.toString(Charsets.UTF_8)
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                )
            )
        )

    private fun parseCertificate(pem: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(pem)
        ) as X509Certificate
}
