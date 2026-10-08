package com.example.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Powers the Grenn\u00F6 GR-R40SMART on while it is in standby and TV Dash cannot answer.
 * Layer 1 is the phone's own infrared blaster. Layer 2 is Wake-on-LAN. This class only does the hardware and
 * network work; the screen decides which layer to try and what to tell the user.
 */
class GrennoTvRemoteManager(context: Context) {
    companion object {
        private const val TAG = "GrennoTvRemote"

        /** NEC protocol carrier frequency. */
        const val CARRIER_HZ = 38_000

        /** Wake-on-LAN: one packet every 750 ms for 45 s, so one lands while the TV's network chip starts up. */
        const val WOL_INTERVAL_MS = 750L
        const val WOL_DURATION_MS = 45_000L
        private val WOL_PORTS = intArrayOf(9, 7)

        /**
         * NEC power toggle in microseconds, starting with a pulse: 9000/4500 header, then 32 bits
         * (pulse 560 + gap 560 = 0, pulse 560 + gap 1690 = 1), then a stop pulse and a 40 ms gap.
         * NOTE: as supplied it decodes to NEC address 0x00 and command 0x00, which is a generic placeholder.
         * Replace it with the code your TV's real remote sends if the TV does not react.
         */
        val GR_R40SMART_POWER_PATTERN: IntArray = intArrayOf(
            9000, 4500, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560,
            560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560,
            1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560,
            560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
            560, 40000
        )
    }

    private val appContext = context.applicationContext
    private val irManager: ConsumerIrManager? =
        appContext.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var wolJob: Job? = null

    /** True when this phone has an infrared emitter at all. */
    fun hasIrEmitter(): Boolean = irManager?.hasIrEmitter() == true

    /** True when the emitter exists and can modulate at 38 kHz (checked against every frequency range it reports). */
    fun verifyHardwareCompatibility(): Boolean {
        val ir = irManager ?: return false
        if (!ir.hasIrEmitter()) return false
        val ranges = ir.carrierFrequencies
        if (ranges.isNullOrEmpty()) {
            Log.w(TAG, "The IR emitter reports no carrier frequency ranges")
            return false
        }
        return ranges.any { CARRIER_HZ in it.minFrequency..it.maxFrequency }
    }

    /** Sends the power-toggle pattern. Returns false, with the reason in the log, if the emitter refuses. */
    fun transmitPowerToggle(): Boolean {
        val ir = irManager
        if (ir == null || !ir.hasIrEmitter()) {
            Log.w(TAG, "No IR emitter on this phone")
            return false
        }
        return try {
            ir.transmit(CARRIER_HZ, GR_R40SMART_POWER_PATTERN)
            true
        } catch (e: Exception) {
            Log.e(TAG, "IR transmit failed (emitter or HAL error)", e)
            false
        }
    }

    /**
     * Sends a Wake-on-LAN magic packet every 750 ms for 45 seconds without blocking the caller.
     * Returns false right away if [macAddress] is not a valid MAC. The flood stops early when [stopWhen] reports
     * that the TV answers. [onFinished] runs on the main thread with true if the TV woke up, false if it did not.
     * Starting a new flood cancels an unfinished one.
     */
    fun executeWakeOnLanFlood(
        macAddress: String,
        stopWhen: (suspend () -> Boolean)? = null,
        onFinished: ((Boolean) -> Unit)? = null
    ): Boolean {
        val mac = parseMac(macAddress) ?: return false
        wolJob?.cancel()
        wolJob = scope.launch {
            val reached = AtomicBoolean(false)
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val payload = magicPacket(mac)
                    val targets = wolTargets()
                    coroutineScope {
                        val sender = launch {
                            val end = SystemClock.elapsedRealtime() + WOL_DURATION_MS
                            var tick = 0
                            while (isActive && SystemClock.elapsedRealtime() <= end) {
                                // One packet per tick; rotate through the broadcast addresses and ports.
                                val (address, port) = targets[tick++ % targets.size]
                                try {
                                    socket.send(DatagramPacket(payload, payload.size, address, port))
                                } catch (e: IOException) {
                                    Log.w(TAG, "Magic packet not sent", e)
                                }
                                delay(WOL_INTERVAL_MS)
                            }
                        }
                        if (stopWhen != null) launch {
                            while (sender.isActive) {
                                delay(2_000)
                                if (!sender.isActive) break
                                if (stopWhen()) { reached.set(true); sender.cancel() }
                            }
                        }
                    }
                }
                notify(onFinished, reached.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Wake-on-LAN flood failed", e)
            }
        }
        return true
    }

    fun cancelWakeOnLan() { wolJob?.cancel() }

    /** Accepts AA:BB:CC:DD:EE:FF, AA-BB-CC-DD-EE-FF or AABBCCDDEEFF. Returns the 6 bytes, or null if invalid. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return if (bytes.all { it == 0.toByte() }) null else bytes
    }

    /** 102 bytes: six 0xFF, then the MAC address 16 times. */
    private fun magicPacket(mac: ByteArray): ByteArray {
        val packet = ByteArray(6 + 16 * mac.size)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (copy in 0 until 16) System.arraycopy(mac, 0, packet, 6 + copy * mac.size, mac.size)
        return packet
    }

    /** Where to send: the global broadcast address and this Wi-Fi's own broadcast address, on ports 9 and 7. */
    private fun wolTargets(): List<Pair<InetAddress, Int>> {
        val addresses = LinkedHashSet<InetAddress>()
        addresses += InetAddress.getByName("255.255.255.255")
        try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { link ->
                val a = link.address
                if (a is Inet4Address && link.prefixLength in 8..30) addresses += subnetBroadcast(a, link.prefixLength)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read this network's broadcast address", e)
        }
        return WOL_PORTS.flatMap { port -> addresses.map { it to port } }
    }

    private fun subnetBroadcast(address: Inet4Address, prefix: Int): InetAddress {
        val ip = address.address
        var value = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or
            ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
        value = value or (-1 shl (32 - prefix)).inv()
        return InetAddress.getByAddress(
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        )
    }

    private fun notify(callback: ((Boolean) -> Unit)?, reached: Boolean) {
        if (callback != null) mainHandler.post { callback(reached) }
    }
}
