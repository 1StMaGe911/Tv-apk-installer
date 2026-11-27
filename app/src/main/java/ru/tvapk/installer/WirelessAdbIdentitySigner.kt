package ru.tvapk.installer

import android.content.Context
import com.flyfishxu.kadb.cert.KadbIdentitySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.wire.AdbPublicKeyCodec
import tv.cleanyour.adb.wire.AdbSignature
import tv.cleanyour.adb.wire.AdbSigner
import tv.cleanyour.adb.wire.AuthToken
import tv.cleanyour.adb.wire.KeyBacking
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Uses the exact RSA identity that completed Wireless Debugging pairing for the
 * subsequent ADB AUTH exchange. The TLS client certificate and ADB AUTH key must
 * be the same key, as they are in the Android platform ADB host implementation.
 */
internal class WirelessAdbIdentitySigner(
    private val context: Context,
    identity: KadbIdentitySnapshot,
    private val resetIdentity: suspend () -> Unit,
) : AdbSigner {
    private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                identity.privateKeyPem.toString(Charsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
            )
        )
    )
    private val publicKey = (CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(identity.certificatePem)).publicKey as RSAPublicKey)

    override val publicKeyLine: String get() = AdbPublicKeyCodec.line(publicKey)
    override val fingerprint: String get() = AdbPublicKeyCodec.fingerprint(publicKey)
    override val backing: KeyBacking = KeyBacking.WRAPPED_SOFTWARE

    override suspend fun sign(token: AuthToken): ByteArray = withContext(Dispatchers.IO) {
        AdbSignature.sign(privateKey, token)
    }

    override suspend fun reset() = withContext(Dispatchers.IO) { resetIdentity() }
}
