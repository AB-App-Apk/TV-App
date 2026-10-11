package com.example.tvremote

import android.os.SystemClock
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
 * One PIN-protected request to TV Dash. The same request text works on both roads: over Wi-Fi as an HTTP request,
 * and over the Bluetooth data channel (see [BluetoothDataClient]) when Wi-Fi cannot reach the TV. Everything that
 * talks to the TV goes through here, so every feature gets the Bluetooth road without knowing about it.
 * Never throws; failures become [TvReply].
 */
class TvLink(
    private val host: () -> String,
    private val pin: () -> String,
    private val bluetooth: BluetoothDataClient? = null
) {
    private companion object {
        /** After Wi-Fi fails, ask over Bluetooth first for this long instead of waiting for Wi-Fi to time out again. */
        const val WIFI_BACKOFF_MS = 10_000L
    }

    private val _carrier = MutableStateFlow(Carrier.WIFI)
    val carrier: StateFlow<Carrier> = _carrier.asStateFlow()

    @Volatile private var wifiRetryAt = 0L

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
        if (address.isBlank()) return@withContext TvReply.Unreachable
        val sep = if ('?' in path) '&' else '?'
        try {
            val c = URL("http://$address:$TV_PORT/$path${sep}pin=${pin()}").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2_000
                c.readTimeout = 4_000
                if (c.responseCode == HttpURLConnection.HTTP_OK) {
                    TvReply.Ok(c.inputStream.bufferedReader().use { it.readText() })
                } else {
                    TvReply.Rejected(c.responseCode)
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
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
