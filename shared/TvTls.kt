package com.example.tvlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for the Wi-Fi link between TvRemote and TvDash. This file is compiled into both apps from the shared/ folder and
 * uses only the JDK, so it also runs in plain JVM unit tests.
 *
 * Trust model: TvDash makes one self-signed EC P-256 certificate and keeps it. Nobody trusts it through a certificate
 * authority. Instead the phone pins its SHA-256 fingerprint after the person has compared a short security code
 * on the TV screen with the one on the phone. After that the phone refuses any other certificate, so a device on the
 * same Wi-Fi cannot read or change the traffic, and the PIN is never sent to a peer that was not verified.
 */
object TvTls {
    /** The security code shows this many hex characters (64 bits): too many to forge while a person compares them. */
    const val CODE_HEX_CHARS = 16

    private const val FINGERPRINT_HEX_CHARS = 64
    private const val ALIAS = "tvdash"
    private const val HEX = "0123456789abcdef"
    private const val COMMON_NAME = "TV Dash"

    /** The certificate the TV presented is not the one that was verified. */
    class PinMismatchException(message: String) : CertificateException(message)

    /** TvDash's private key and its self-signed certificate. */
    class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
        /** SHA-256 of the certificate, 64 lowercase hex characters. */
        val fingerprint: String = TvTls.fingerprintOf(certificate.encoded)

        /** The short code both screens show, for example "A1B2 C3D4 E5F6 7788". */
        val shortCode: String get() = TvTls.shortCode(fingerprint)
    }

    // ---------------------------------------------------------------- fingerprints

    fun fingerprintOf(der: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(der))

    /** Lowercase hex with everything but letters and digits removed, so "A1:B2 c3" and "a1b2c3" compare equal. */
    fun normalize(fingerprint: String): String =
    fingerprint
        .filterNot { it.isWhitespace() || it == ':' || it == '-' }
        .lowercase()

private fun isFullFingerprint(value: String): Boolean =
    value.length == FINGERPRINT_HEX_CHARS &&
        value.all { it in '0'..'9' || it in 'a'..'f' }

fun sameFingerprint(a: String, b: String): Boolean {
    val x = normalize(a)
    val y = normalize(b)

    return isFullFingerprint(x) &&
        isFullFingerprint(y) &&
        MessageDigest.isEqual(
            x.toByteArray(Charsets.US_ASCII),
            y.toByteArray(Charsets.US_ASCII)
        )
}

    /** The first [CODE_HEX_CHARS] characters in groups of four, upper case: what the person compares. */
    fun shortCode(fingerprint: String): String =
        normalize(fingerprint).take(CODE_HEX_CHARS).uppercase().chunked(4).joinToString(" ")

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- the TV's identity

    /**
     * Makes a new EC P-256 key and a self-signed X.509 certificate for it. The certificate is written by hand
     * (a few DER elements) because Android has no public API for it. It is version 1, with no extensions, and its dates
     * are fixed (2020 to 2070) so a TV with a wrong clock cannot produce a "not yet valid" certificate. Nothing checks
     * the dates anyway: the phone trusts the fingerprint.
     */
    fun generate(): Identity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keys = generator.generateKeyPair()

        val sigAlg = der(0x30, der(0x06, ECDSA_WITH_SHA256))
        val subject = name(COMMON_NAME)
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE) // positive, never zero
        val validity = der(
            0x30,
            concat(
                der(0x17, "200101000000Z".toByteArray(Charsets.US_ASCII)), // UTCTime: 2020-01-01
                der(0x18, "20700101000000Z".toByteArray(Charsets.US_ASCII)) // GeneralizedTime: 2070-01-01
            )
        )
        val tbs = der(
            0x30,
            concat(
                der(0x02, serial.toByteArray()),
                sigAlg,
                subject, // issuer = subject: self-signed
                validity,
                subject,
                keys.public.encoded // already a SubjectPublicKeyInfo
            )
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(tbs)
        val signature = signer.sign()
        val encoded = der(0x30, concat(tbs, sigAlg, der(0x03, concat(byteArrayOf(0), signature))))
        return Identity(keys.private, parse(encoded))
    }

    /** Rebuilds an identity from [privateKey]'s PKCS#8 bytes and the certificate's DER bytes, as saved earlier. */
    fun restore(privateKeyPkcs8: ByteArray, certificateDer: ByteArray): Identity =
        Identity(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8)), parse(certificateDer))

    private fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    // 1.2.840.10045.4.3.2 (ecdsa-with-SHA256) and 2.5.4.3 (commonName), as DER object identifier contents.
    private val ECDSA_WITH_SHA256 =
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val COMMON_NAME_OID = byteArrayOf(0x55, 0x04, 0x03)

    private fun name(commonName: String): ByteArray {
        val attribute = der(0x30, concat(der(0x06, COMMON_NAME_OID), der(0x0C, commonName.toByteArray(Charsets.UTF_8))))
        return der(0x30, der(0x31, attribute))
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part, 0, part.size)
        return out.toByteArray()
    }

    /** One DER element: tag, length, contents. */
    private fun der(tag: Int, body: ByteArray): ByteArray {
        val length = when {
            body.size < 0x80 -> byteArrayOf(body.size.toByte())
            body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            body.size < 0x10000 -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            else -> throw IllegalArgumentException("DER element too long")
        }
        return concat(byteArrayOf(tag.toByte()), length, body)
    }

    // ---------------------------------------------------------------- TV side

    /** Only modern protocols: TLS 1.3 and 1.2, whichever of them this device supports. */
    fun enabledProtocols(supported: Array<String>): Array<String> {
        val usable = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        check(usable.isNotEmpty()) { "This device supports neither TLS 1.3 nor TLS 1.2" }
        return usable.toTypedArray()
    }

    /** A context that answers every handshake with [identity]. Use it for the TV's server socket. */
    fun serverContext(identity: Identity): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf<KeyManager>(SingleKeyManager(identity)), null, SecureRandom())
        return context
    }

    /** Offers exactly one EC key and certificate, so there is no key store to load, to open, or to get wrong. */
    private class SingleKeyManager(private val identity: Identity) : X509KeyManager {
        private fun fits(keyType: String?) = keyType != null && keyType.uppercase().startsWith("EC")

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (fits(keyType)) arrayOf(ALIAS) else null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (fits(keyType)) ALIAS else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == ALIAS) arrayOf(identity.certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null
    }

    // ---------------------------------------------------------------- phone side

    /** Accepts one certificate only: the one whose SHA-256 fingerprint is [expected]. */
    class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("TV Dash does not use client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw PinMismatchException("The TV sent no certificate")
            if (!sameFingerprint(fingerprintOf(leaf.encoded), expected)) {
                throw PinMismatchException("The TV's certificate is not the one that was verified")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Volatile private var cachedFactory: Pair<String, SSLSocketFactory>? = null

    /** A client socket factory that trusts only [fingerprint]. The last one is kept, so TLS sessions can be resumed. */
    fun pinnedSocketFactory(fingerprint: String): SSLSocketFactory {
        val key = normalize(fingerprint)
        cachedFactory?.let { if (it.first == key) return it.second }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(PinnedTrustManager(key)), SecureRandom())
        return context.socketFactory.also { cachedFactory = key to it }
    }

    /**
     * Connects to [host] and returns the SHA-256 fingerprint of the certificate it presents, or null if no TLS
     * handshake could be completed. It trusts any certificate on purpose, because its only job is to read the
     * fingerprint so the person can compare the security code. It sends nothing, and the fingerprint is used only after
     * the person confirms it. Blocks, so call it off the main thread.
     */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun fetchFingerprint(host: String, port: Int, timeoutMs: Int = 3_000): String? {
        val readOnly = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(readOnly), SecureRandom())
            (context.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                fingerprintOf(socket.session.peerCertificates.first().encoded)
            }
        } catch (e: IOException) {
            null
        }
    }

    /** True when [error] or anything that caused it is a certificate problem (the pin did not match). */
    fun isCertificateProblem(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is CertificateException }
}
}
