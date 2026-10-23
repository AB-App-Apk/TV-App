package com.example.tvremote

import android.os.SystemClock
import com.example.tvlink.TvProtocol
import com.example.tvlink.TvTls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

const val TV_PORT = 8765

/** What came back from one request to the TV. */
sealed interface TvReply {
    data class Ok(val body: String) : TvReply
    data class Rejected(val http: Int) : TvReply
    data object Unreachable : TvReply
}

/** The text of a successful answer, or null when the TV refused or did not answer. */
fun TvReply.bodyOrNull(): String? = (this as? TvReply.Ok)?.body

/** Which road the last answer travelled on. */
enum class Carrier { WIFI, BLUETOOTH }

/**
 * One PIN-protected request to TV Dash. The same request text works on both roads: over Wi-Fi as an HTTPS request,
 * and over the Bluetooth data channel (see [BluetoothDataClient]) when Wi-Fi cannot reach the TV. Everything that
 * talks to the TV goes through here, so every feature gets the Bluetooth road without knowing about it.
 * Never throws; failures become [TvReply].
 *
 * The Wi-Fi road is encrypted and trusts exactly one certificate: the one whose SHA-256 fingerprint [fingerprint]
 * returns, which the person verified during setup. While no fingerprint is saved, nothing is sent over Wi-Fi at all,
 * so the PIN can never go to a TV that was not verified.
 */
class TvLink(
    private val host: () -> String,
    private val pin: () -> String,
    private val bluetooth: BluetoothDataClient? = null,
    private val fingerprint: () -> String = { "" }
) {
    private companion object {
        /** After Wi-Fi fails, ask over Bluetooth first for this long instead of waiting for Wi-Fi to time out again. */
        const val WIFI_BACKOFF_MS = 10_000L
    }

    private val _carrier = MutableStateFlow(Carrier.WIFI)
    val carrier: StateFlow<Carrier> = _carrier.asStateFlow()

    @Volatile private var wifiRetryAt = 0L

    /** True when the last Wi-Fi attempt failed because the TV's certificate is not the one that was verified. */
    @Volatile var certificateMismatch = false
        private set

    /** True when a TV address is saved but its certificate was never verified, so Wi-Fi is switched off. */
    val needsVerification: Boolean get() = host().isNotBlank() && fingerprint().isBlank()

    suspend fun get(path: String): TvReply {
        if (SystemClock.elapsedRealtime() >= wifiRetryAt) {
            val viaWifi = overWifi(path)
            if (viaWifi !is TvReply.Unreachable) {
                _carrier.value = Carrier.WIFI
                return viaWifi
            }
            wifiRetryAt = SystemClock.elapsedRealtime() + WIFI_BACKOFF_MS
            return overBluetooth(path) ?: TvReply.Unreachable
        }
        // Wi-Fi failed a moment ago: Bluetooth first, and Wi-Fi again only if Bluetooth cannot answer either.
        overBluetooth(path)?.let { return it }
        val viaWifi = overWifi(path)
        if (viaWifi !is TvReply.Unreachable) {
            _carrier.value = Carrier.WIFI
            wifiRetryAt = 0L
        }
        return viaWifi
    }

    private suspend fun overBluetooth(path: String): TvReply? {
        val reply = bluetooth?.request(path) ?: return null
        _carrier.value = Carrier.BLUETOOTH
        return reply
    }

    private suspend fun overWifi(path: String): TvReply = withContext(Dispatchers.IO) {
        val address = host()
        val pinned = fingerprint()
        // No verified certificate, no request: the PIN must never be sent to a peer that was not verified.
        if (address.isBlank() || pinned.isBlank()) return@withContext TvReply.Unreachable
        try {
            val c = URL(TvProtocol.buildWifiUrl(address, TV_PORT, path)).openConnection() as HttpsURLConnection
            try {
                c.sslSocketFactory = TvTls.pinnedSocketFactory(pinned)
                // The address is an IP, which a certificate for "TV Dash" cannot match by name. The pin is the identity check.
                c.hostnameVerifier = HostnameVerifier { _, _ -> true }
                c.requestMethod = "GET"
                c.setRequestProperty(TvProtocol.PIN_HEADER, pin())
                c.connectTimeout = 2_000
                c.readTimeout = 4_000
                val reply = if (c.responseCode == HttpURLConnection.HTTP_OK) {
                    TvReply.Ok(c.inputStream.bufferedReader().use { it.readText() })
                } else {
                    TvReply.Rejected(c.responseCode)
                }
                certificateMismatch = false
                reply
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            certificateMismatch = TvTls.isCertificateProblem(e)
            TvReply.Unreachable
        }
    }
}

/**
 * Strict FIFO. One consumer coroutine drains the channel, so commands run one at a time in the order they were
 * submitted and a later command can never overtake an earlier one.
 */
class CommandQueue(scope: CoroutineScope) {
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failing job must not stop the queue; jobs report their own errors.
                }
            }
        }
    }

    fun submit(job: suspend () -> Unit) { jobs.trySend(job) }
    fun close() { jobs.close() }
}
