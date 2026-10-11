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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = appContext.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var socket: BluetoothSocket? = null

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** True when Bluetooth is on, allowed, and a TV has been chosen. */
    fun isAvailable(): Boolean = try {
        adapter?.isEnabled == true && hasPermission() && !prefs.getString(PREF_TARGET, null).isNullOrEmpty()
    } catch (e: SecurityException) {
        false
    }

    /**
     * Sends one request and returns the TV's answer, or null when the Bluetooth road is not set up or the TV did not
     * answer. A stale link is dropped and the request is retried once on a fresh one.
     */
    suspend fun request(path: String): TvReply? = lock.withLock {
        if (!isAvailable()) return@withLock null
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                try {
                    val link = connected() ?: return@withContext null
                    return@withContext exchange(link, path)
                } catch (e: IOException) {
                    Log.w(TAG, "Bluetooth request failed (attempt ${attempt + 1}): ${e.message}")
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

    /** Closes the link. Call when the screen is destroyed. */
    fun close() = closeLink()

    private fun closeLink() {
        runCatching { socket?.close() }
        socket = null
    }

    /** Reuses the open link, or opens a new one to the chosen paired TV. */
    private suspend fun connected(): BluetoothSocket? {
        socket?.let { if (it.isConnected) return it }
        closeLink()
        val a = adapter ?: return null
        val address = prefs.getString(PREF_TARGET, null) ?: return null
        val device = a.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) return null
        a.cancelDiscovery() // scanning slows a connection down
        val s = device.createRfcommSocketToServiceRecord(TvProtocol.BT_UUID)
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
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        socket = s
        return s
    }

    /** The request text is the same as over Wi-Fi; the answer is read by its Content-Length. */
    private suspend fun exchange(s: BluetoothSocket, path: String): TvReply = coroutineScope {
        // BluetoothSocket has no read timeout, so a watchdog closes it if the TV never replies.
        val watchdog = launch {
            delay(REPLY_TIMEOUT_MS)
            runCatching { s.close() }
        }
        try {
            val sep = if ('?' in path) '&' else '?'
            val out = s.outputStream
            val input = s.inputStream
            out.write("GET /$path${sep}pin=${pin()} HTTP/1.1\r\n\r\n".toByteArray())
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
