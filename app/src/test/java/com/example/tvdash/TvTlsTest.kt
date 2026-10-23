package com.example.tvdash

import com.example.tvlink.TvTls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class TvTlsTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /** A tiny TLS server using the same context the TV uses: it answers one line with "echo:<line>". */
    private fun echoServer(identity: TvTls.Identity): SSLServerSocket {
        val server = TvTls.serverContext(identity).serverSocketFactory.createServerSocket() as SSLServerSocket
        server.reuseAddress = true
        server.enabledProtocols = TvTls.enabledProtocols(server.supportedProtocols)
        server.bind(InetSocketAddress(loopback, 0))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) {
                    try {
                        client.soTimeout = 3_000
                        (client as SSLSocket).startHandshake()
                        val line = client.getInputStream().bufferedReader().readLine()
                        client.getOutputStream().write("echo:$line\n".toByteArray())
                        client.getOutputStream().flush()
                    } catch (e: IOException) {
                        // A failed handshake is an expected outcome in some tests.
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
        return server
    }

    @Test
    fun generatedCertificateIsSelfSignedEcWithTheExpectedName() {
        val id = TvTls.generate()

        id.certificate.verify(id.certificate.publicKey) // throws if the signature does not match its own key
        assertEquals("EC", id.certificate.publicKey.algorithm)
        assertEquals("SHA256withECDSA", id.certificate.sigAlgName)
        assertTrue(id.certificate.subjectX500Principal.name.contains("CN=TV Dash"))
        assertEquals(id.certificate.subjectX500Principal, id.certificate.issuerX500Principal)
    }

    @Test
    fun everyIdentityIsDifferent() {
        assertNotEquals(TvTls.generate().fingerprint, TvTls.generate().fingerprint)
    }

    @Test
    fun fingerprintAndSecurityCodeHaveTheShownFormat() {
        val id = TvTls.generate()

        assertTrue(Regex("[0-9a-f]{64}").matches(id.fingerprint))
        assertTrue(Regex("[0-9A-F]{4}( [0-9A-F]{4}){3}").matches(id.shortCode))
        assertEquals(id.fingerprint.take(16).uppercase(), id.shortCode.replace(" ", ""))
    }

    @Test
    fun fingerprintComparisonIgnoresCaseAndSeparatorsButNeedsAFullFingerprint() {
        val fp = TvTls.generate().fingerprint
        val spaced = fp.uppercase().chunked(2).joinToString(":")

        assertTrue(TvTls.sameFingerprint(fp, spaced))
        assertTrue(!TvTls.sameFingerprint(fp, fp.take(16)))
        assertTrue(!TvTls.sameFingerprint("", ""))
        assertTrue(!TvTls.sameFingerprint(fp, TvTls.generate().fingerprint))
    }

    @Test
    fun restoredIdentityKeepsItsFingerprintAndKey() {
        val id = TvTls.generate()

        val back = TvTls.restore(id.privateKey.encoded, id.certificate.encoded)

        assertEquals(id.fingerprint, back.fingerprint)
        assertEquals(id.privateKey.algorithm, back.privateKey.algorithm)
    }

    @Test
    fun pinnedTrustManagerAcceptsOnlyTheVerifiedCertificate() {
        val tv = TvTls.generate()
        val stranger = TvTls.generate()
        val pinned = TvTls.PinnedTrustManager(tv.fingerprint)

        pinned.checkServerTrusted(arrayOf(tv.certificate), "ECDSA")
        assertThrows(TvTls.PinMismatchException::class.java) {
            pinned.checkServerTrusted(arrayOf(stranger.certificate), "ECDSA")
        }
        assertThrows(TvTls.PinMismatchException::class.java) { pinned.checkServerTrusted(emptyArray<X509Certificate>(), "ECDSA") }
    }

    @Test
    fun encryptedLinkWorksWhenThePinMatches() {
        val tv = TvTls.generate()
        val server = echoServer(tv)
        try {
            val socket = TvTls.pinnedSocketFactory(tv.fingerprint).createSocket(loopback, server.localPort) as SSLSocket
            socket.use {
                it.soTimeout = 3_000
                it.startHandshake()
                assertTrue(it.session.protocol == "TLSv1.3" || it.session.protocol == "TLSv1.2")
                it.getOutputStream().write("hello\n".toByteArray())
                it.getOutputStream().flush()
                assertEquals("echo:hello", it.getInputStream().bufferedReader().readLine())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun handshakeFailsAsACertificateProblemWhenAnotherCertificateAnswers() {
        val tv = TvTls.generate()
        val verifiedEarlier = TvTls.generate()
        val server = echoServer(tv)
        try {
            val socket = TvTls.pinnedSocketFactory(verifiedEarlier.fingerprint).createSocket(loopback, server.localPort) as SSLSocket
            socket.use {
                it.soTimeout = 3_000
                try {
                    it.startHandshake()
                    fail("A certificate that was not verified must be refused")
                } catch (e: IOException) {
                    assertTrue(TvTls.isCertificateProblem(e))
                }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun fetchFingerprintReadsTheServersCertificateWithoutSendingAnything() {
        val tv = TvTls.generate()
        val server = echoServer(tv)
        try {
            assertEquals(tv.fingerprint, TvTls.fetchFingerprint("127.0.0.1", server.localPort))
        } finally {
            server.close()
        }
    }

    @Test
    fun fetchFingerprintReturnsNullWhenNoServicePortIsAvailable() {
        // Port 0 is never a listening destination port; unlike a just-closed ephemeral port,
        // it cannot be claimed by another local listener between closing and connecting.
        assertNull(TvTls.fetchFingerprint("127.0.0.1", 0, timeoutMs = 1_000))
    }

    @Test
    fun aPlainHttpRequestIsNeverAnsweredInPlainText() {
        val tv = TvTls.generate()
        val server = echoServer(tv)
        try {
            Socket("127.0.0.1", server.localPort).use { plain ->
                plain.soTimeout = 3_000
                plain.getOutputStream().write("GET /ping HTTP/1.1\r\nX-TV-PIN: 1234\r\n\r\n".toByteArray())
                plain.getOutputStream().flush()
                val first = try { plain.getInputStream().read() } catch (e: IOException) { -1 }
                // Closed (-1) or a TLS alert record (21): never the "H" of an "HTTP/1.1 200" answer.
                assertTrue(first == -1 || first == 21)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun onlyModernProtocolsAreEnabled() {
        val enabled = TvTls.enabledProtocols(arrayOf("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2"))

        assertEquals(listOf("TLSv1.2"), enabled.toList())
        assertNotNull(TvTls.enabledProtocols(arrayOf("TLSv1.3", "TLSv1.2")))
        assertThrows(IllegalStateException::class.java) { TvTls.enabledProtocols(arrayOf("SSLv3", "TLSv1")) }
    }
}
