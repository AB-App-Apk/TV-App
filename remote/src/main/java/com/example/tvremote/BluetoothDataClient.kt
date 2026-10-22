package com.example.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tvlink.TvProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream

/**
 * The phone's side of the Bluetooth data channel. It opens a classic Bluetooth (RFCOMM) link to TV Dash on the
 * paired TV and sends the same requests TV Dash answers over Wi-Fi, so the Apps list, YouTube search, Settings
 * and every other request work without Wi-Fi or internet.
 *
 * One link stays open and is reused. Requests go one at a time. Needs the TV paired with this phone, the TV chosen
 * under "Choose TV", the Bluetooth permission, and TV Dash running on the TV.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is guarded by hasPermission() or catches SecurityException.
class BluetoothDataClient(context: Context, private val pin: () -> String) {
    private companion object {
        const val TAG = "BluetoothData"
        const val PREF_TARGET = "bt_target"
        const val CONNECT_TIMEOUT_MS = 6_000L
        const val REPLY_TIMEOUT_MS = 6_000L
        const val MAX_LINE = 4096
        const val MAX_BODY = 1 shl 20

        // Keep the RFCOMM socket ready while the normal Wi-Fi route is working.
        const val WARM_CHECK_MS = 15_000L
        const val WARM_NOT_READY_MS = 10_000L
        const val WARM_RETRY_MIN_MS = 1_000L
        const val WARM_RETRY_MAX_MS = 30_000L
        const val WARM_PING_TIMEOUT_MS = 2_500L
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = appContext.getSharedPreferences("remote", Context.MODE_PRIVATE)
    // Requests and standby connects share this lock so only one RFCOMM connection/request is active at a time.
    private val lock = Mutex()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var socketTarget: String? = null
    @Volatile private var connectingSocket: BluetoothSocket? = null
    @Volatile private var warmConnectingSocket: BluetoothSocket? = null
    @Volatile private var requestWaiting = false
    @Volatile private var closed = false

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmNudge = Channel<Unit>(Channel.CONFLATED)
    private val targetListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_TARGET) warmNudge.trySend(Unit)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(targetListener)
        // The saved target can appear later during Bluetooth setup; the preference listener wakes this loop immediately.
        warmScope.launch { maintainWarmLink() }
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** True when Bluetooth is on, allowed, and a TV has been chosen. */
    fun isAvailable(): Boolean = try {
        adapter?.isEnabled == true && hasPermission() && !prefs.getString(PREF_TARGET, null).isNullOrEmpty()
    } catch (e: SecurityException) {
        false
    }

    /**
     * Maintains a connected RFCOMM standby while Bluetooth is configured, even when Wi-Fi currently works.
     * Failed connects use backoff; selecting a different TV or saving the target wakes the loop immediately.
     */
    private suspend fun maintainWarmLink() {
        var retryMs = WARM_RETRY_MIN_MS
        while (warmScope.isActive && !closed) {
            if (!isAvailable()) {
                try {
                    lock.withLock { closeLink() }
                } catch (e: CancellationException) {
                    throw e
                }
                waitForWarmNudge(WARM_NOT_READY_MS)
                continue
            }

            val ready = try {
                lock.withLock {
                    if (closed || !isAvailable()) {
                        closeLink()
                        false
                    } else {
                        try {
                            val active = connected(warming = true) ?: return@withLock false
                            // A short heartbeat detects a peer-closed socket before the next real request.
                            // If a user request is waiting, leave the warm socket alone and release the lock quickly.
                            if (!requestWaiting) {
                                exchange(active, "ping", WARM_PING_TIMEOUT_MS)
                            }
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "RFCOMM standby/heartbeat failed: " + e.message)
                            closeLink()
                            false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "RFCOMM standby failed: " + e.message)
                false
            }

            if (ready) {
                retryMs = WARM_RETRY_MIN_MS
                waitForWarmNudge(WARM_CHECK_MS)
            } else {
                waitForWarmNudge(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(WARM_RETRY_MAX_MS)
            }
        }
    }

    private suspend fun waitForWarmNudge(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { warmNudge.receive() }
    }

    /**
     * Sends one request. If a standby connection is still being opened when Wi-Fi fails, the real request takes
     * priority: it closes that in-flight standby attempt and opens its own connection without waiting for its timeout.
     */
    suspend fun request(path: String): TvReply? {
        if (closed) return null
        requestWaiting = true
        warmConnectingSocket?.let { runCatching { it.close() } }
        try {
            return lock.withLock {
                if (closed || !isAvailable()) return@withLock null
                withContext(Dispatchers.IO) {
                    repeat(2) { attempt ->
                        try {
                            val link = connected() ?: return@withContext null
                            return@withContext exchange(link, path)
                        } catch (e: IOException) {
                            Log.w(TAG, "Bluetooth request failed (attempt " + (attempt + 1) + "): " + e.message)
                            closeLink()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Bluetooth permission is missing", e)
                            closeLink()
                            return@withContext null
                        }
                    }
                    null
                }
            }
        } finally {
            requestWaiting = false
            warmNudge.trySend(Unit)
        }
    }

    /** Wakes the standby loop immediately after app foregrounding or a service restart. */
    fun reconnectNow() {
        if (!closed) warmNudge.trySend(Unit)
    }

    /** Closes the background standby worker and its socket. Call when the screen's graph is destroyed. */
    fun close() {
        if (closed) return
        closed = true
        prefs.unregisterOnSharedPreferenceChangeListener(targetListener)
        warmScope.cancel()
        runCatching { warmConnectingSocket?.close() }
        runCatching { connectingSocket?.close() }
        warmNudge.close()
        closeLink()
    }

    private fun closeLink() {
        val old = socket
        socket = null
        socketTarget = null
        runCatching { old?.close() }
    }

    /** Reuses the open link for the currently selected TV, or opens a new one. */
    private suspend fun connected(warming: Boolean = false): BluetoothSocket? {
        if (closed) return null
        val address = prefs.getString(PREF_TARGET, null) ?: return null
        socket?.let { if (it.isConnected && socketTarget == address) return it }
        closeLink()

        val a = adapter ?: return null
        val device = a.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) return null
        a.cancelDiscovery() // scanning slows a connection down
        val s = device.createRfcommSocketToServiceRecord(TvProtocol.BT_UUID)
        if (warming) warmConnectingSocket = s else connectingSocket = s
        try {
            coroutineScope {
                // connect() cannot be cancelled, so a watchdog closes the socket if the TV never answers.
                val watchdog = launch {
                    delay(CONNECT_TIMEOUT_MS)
                    runCatching { s.close() }
                }
                try {
                    s.connect()
                } finally {
                    watchdog.cancel()
                }
            }
            if (closed) throw IOException("Bluetooth data client is closed")
            if (!s.isConnected) throw IOException("Bluetooth connection did not open")
            socket = s
            socketTarget = address
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        } finally {
            if (warming && warmConnectingSocket === s) warmConnectingSocket = null
            if (!warming && connectingSocket === s) connectingSocket = null
        }
    }

    /** The request text is the same as over Wi-Fi; the answer is read by its Content-Length. */
    private suspend fun exchange(
        s: BluetoothSocket,
        path: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS
    ): TvReply = coroutineScope {
        // BluetoothSocket has no read timeout, so a watchdog closes it if the TV never replies.
        val watchdog = launch {
            delay(timeoutMs)
            runCatching { s.close() }
        }
        try {
            val out = s.outputStream
            val input = s.inputStream
            out.write("GET /$path HTTP/1.1\r\n${TvProtocol.PIN_HEADER}: ${pin()}\r\n\r\n".toByteArray())
            out.flush()

            val status = readLine(input) ?: throw IOException("The TV closed the Bluetooth link")
            var length = 0
            while (true) {
                val header = readLine(input) ?: throw IOException("The TV closed the Bluetooth link")
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toIntOrNull() ?: throw IOException("Bad reply from the TV")
                }
            }
            if (length < 0 || length > MAX_BODY) throw IOException("Bad reply size from the TV")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw IOException("The TV closed the Bluetooth link")
                read += n
            }
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad reply from the TV")
            if (code == 200) TvReply.Ok(String(body, Charsets.UTF_8)) else TvReply.Rejected(code)
        } finally {
            watchdog.cancel()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) throw IOException("Reply line too long")
            sb.append(ch.toChar())
        }
    }
}
