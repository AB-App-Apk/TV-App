package com.example.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Bluetooth fallback: the phone registers as a Bluetooth keyboard with media keys (the classic HID Device
 * profile, Android 9+), and the TV, which must already be paired, treats it like any keyboard. No TV Dash needed.
 *
 * Arrow keys, Enter and typing are keyboard keys. Consumer Control has no D-pad, so it carries Home, Back,
 * volume, power and the media keys.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is guarded by hasPermission() or catches SecurityException.
class BluetoothHidTransport(context: Context) : RemoteTransport {
    companion object {
        private const val TAG = "BluetoothHid"
        private const val PREF_TARGET = "bt_target"
        private const val REPORT_KEYBOARD = 1
        private const val REPORT_CONSUMER = 2
        private const val KEY_HOLD_MS = 30L
        private const val READY_TIMEOUT_MS = 2_500L

        /** How long to wait for the TV to open the HID channel. */
        const val CONNECT_TIMEOUT_MS = 5_000L

        // Keyboard page (0x07) usages
        private const val KEY_ENTER = 0x28
        private const val KEY_RIGHT = 0x4F
        private const val KEY_LEFT = 0x50
        private const val KEY_DOWN = 0x51
        private const val KEY_UP = 0x52

        // Consumer page (0x0C) usages
        private const val MEDIA_POWER = 0x30
        private const val MEDIA_FORWARD = 0xB3
        private const val MEDIA_REWIND = 0xB4
        private const val MEDIA_MUTE = 0xE2
        private const val MEDIA_VOLUME_UP = 0xE9
        private const val MEDIA_VOLUME_DOWN = 0xEA
        private const val MEDIA_HOME = 0x223
        private const val MEDIA_BACK = 0x224

        /** US layout: index + 0x04 is the HID usage. \u0000 marks the one key we skip (non-US #). */
        private const val UNSHIFTED = "abcdefghijklmnopqrstuvwxyz1234567890\n\u001B\b\t -=[]\\\u0000;'`,./"
        private const val SHIFTED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ!@#\$%^&*()\n\u001B\b\t _+{}|\u0000:\"~<>?"

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /** Composite HID report descriptor: keyboard (report 1) and consumer control (report 2). */
        private val DESCRIPTOR: ByteArray = bytes(
            // --- Keyboard: 1 byte modifiers, 1 reserved byte, 6 key codes ---
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1, 0x01,             // Collection (Application)
            0x85, REPORT_KEYBOARD,  //   Report ID
            0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0, 0x29, 0xE7, //   Usage Minimum/Maximum (Left Control .. Right GUI)
            0x15, 0x00, 0x25, 0x01, //   Logical Minimum 0, Maximum 1
            0x75, 0x01, 0x95, 0x08, //   8 fields of 1 bit
            0x81, 0x02,             //   Input (Data, Variable, Absolute): modifier byte
            0x95, 0x01, 0x75, 0x08, //   1 field of 8 bits
            0x81, 0x01,             //   Input (Constant): reserved byte
            0x95, 0x06, 0x75, 0x08, //   6 fields of 8 bits
            0x15, 0x00, 0x25, 0x65, //   Logical Minimum 0, Maximum 101
            0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
            0x19, 0x00, 0x29, 0x65, //   Usage Minimum/Maximum (0 .. 101)
            0x81, 0x00,             //   Input (Data, Array): key codes
            0xC0,                   // End Collection
            // --- Consumer control: one 16-bit usage at a time ---
            0x05, 0x0C,             // Usage Page (Consumer)
            0x09, 0x01,             // Usage (Consumer Control)
            0xA1, 0x01,             // Collection (Application)
            0x85, REPORT_CONSUMER,  //   Report ID
            0x15, 0x00,             //   Logical Minimum 0
            0x26, 0xFF, 0x03,       //   Logical Maximum 1023
            0x19, 0x00,             //   Usage Minimum 0
            0x2A, 0xFF, 0x03,       //   Usage Maximum 1023
            0x75, 0x10, 0x95, 0x01, //   1 field of 16 bits
            0x81, 0x00,             //   Input (Data, Array)
            0xC0                    // End Collection
        )
    }

    private sealed interface Usage {
        data class Key(val code: Int, val shift: Boolean = false) : Usage
        data class Media(val code: Int) : Usage
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = appContext.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val sendLock = Mutex()

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var registered = false
    @Volatile private var attaching = false
    @Volatile private var connectedAddress: String? = null

    /** The last reason the link could not be used, in words for the screen. */
    @Volatile var lastProblem: String? = null
        private set

    /** Address of the paired TV chosen for Bluetooth. */
    var targetAddress: String?
        get() = prefs.getString(PREF_TARGET, null)
        set(value) { prefs.edit().putString(PREF_TARGET, value).apply() }

    override fun getConnectionState(): StateFlow<ConnectionState> = state.asStateFlow()

    // ---------------------------------------------------------------- capability and permissions

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && adapter != null

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Android 12+ asks for these together as "Nearby devices". Older versions need no prompt. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    /** Makes the phone visible for 2 minutes so the TV can find it when pairing. */
    fun discoverableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)

    fun bondedDevices(): List<BluetoothDevice> = try {
        adapter?.bondedDevices.orEmpty()
            .sortedByDescending { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
    } catch (e: SecurityException) {
        emptyList()
    }

    fun nameOf(device: BluetoothDevice): String = try { device.name ?: device.address } catch (e: SecurityException) { device.address }

    fun targetName(): String? {
        val address = targetAddress ?: return null
        return bondedDevices().firstOrNull { it.address == address }?.let { nameOf(it) } ?: address
    }

    // ---------------------------------------------------------------- HID profile lifecycle

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            attaching = false
            hid = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            registered = false
            attaching = false
            connectedAddress = null
            state.value = ConnectionState.DISCONNECTED
        }
    }

    private val callback: BluetoothHidDevice.Callback by lazy {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                this@BluetoothHidTransport.registered = registered
                if (!registered) state.value = ConnectionState.DISCONNECTED
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
                if (device.address != targetAddress) return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedAddress = device.address
                        state.value = ConnectionState.CONNECTED
                    }
                    BluetoothProfile.STATE_CONNECTING -> state.value = ConnectionState.CONNECTING
                    else -> {
                        connectedAddress = null
                        state.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }
    }

    /** Opens the HID profile and registers this phone as a keyboard and media-key device. Safe to call repeatedly. */
    fun attach() {
        if (!isSupported() || !hasPermission() || hid != null || attaching) return
        val a = adapter ?: return
        if (!a.isEnabled) return
        attaching = true
        try {
            if (!a.getProfileProxy(appContext, listener, BluetoothProfile.HID_DEVICE)) {
                attaching = false
                problem("This phone does not offer Bluetooth keyboard mode.")
            }
        } catch (e: SecurityException) {
            attaching = false
            problem("Allow 'Nearby devices' for TvRemote to use Bluetooth.")
        }
    }

    private fun registerApp() {
        val proxy = hid ?: return
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "TvRemote", "TV remote keys and typing", "TvRemote", BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR
            )
            // Callbacks only flip flags, so the main thread is fine for them.
            val ok = proxy.registerApp(sdp, null, null, ContextCompat.getMainExecutor(appContext), callback)
            if (!ok) problem("Another app is already using this phone as a Bluetooth keyboard.")
        } catch (e: SecurityException) {
            problem("Allow 'Nearby devices' for TvRemote to use Bluetooth.")
        }
    }

    /** Unregisters the keyboard role and closes the HID profile. Call when the screen is destroyed. */
    fun release() {
        val proxy = hid
        try { proxy?.unregisterApp() } catch (e: Exception) { Log.w(TAG, "unregisterApp failed", e) }
        try {
            if (proxy != null) adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        } catch (e: Exception) {
            Log.w(TAG, "closeProfileProxy failed", e)
        }
        hid = null
        registered = false
        attaching = false
        connectedAddress = null
        state.value = ConnectionState.DISCONNECTED
    }

    // ---------------------------------------------------------------- RemoteTransport

    override suspend fun connect(): Boolean {
        lastProblem = null
        if (!isSupported()) return fail("This phone can't act as a Bluetooth keyboard (needs Android 9 or newer).")
        if (!hasPermission()) return fail("Allow 'Nearby devices' for TvRemote to use Bluetooth.")
        val a = adapter ?: return fail("This phone has no Bluetooth.")
        if (!a.isEnabled) return fail("Bluetooth is turned off on the phone.")
        val address = targetAddress ?: return fail("Choose your TV for Bluetooth first (Choose TV).")
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return fail("The saved Bluetooth TV is not valid. Choose it again.")
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) return fail("The TV is not paired with this phone yet.")

        attach()
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (hid == null || !registered) delay(50)
            true
        }
        if (ready != true) return fail(lastProblem ?: "Bluetooth keyboard mode could not start on this phone.")
        if (state.value == ConnectionState.CONNECTED && connectedAddress == address) return true

        state.value = ConnectionState.CONNECTING
        val started = try { hid?.connect(device) == true } catch (e: SecurityException) { false }
        if (!started) return fail("Bluetooth could not start the connection to the TV.")
        val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { state.first { it == ConnectionState.CONNECTED } }
        return if (up != null) true else fail("The TV did not answer over Bluetooth within 5 seconds. Is it on?")
    }

    override suspend fun disconnect() {
        val address = connectedAddress ?: targetAddress
        val proxy = hid
        if (proxy != null && address != null) {
            try {
                adapter?.getRemoteDevice(address)?.let { proxy.disconnect(it) }
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed", e)
            }
        }
        connectedAddress = null
        state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun sendCommand(command: RemoteCommand): DeliveryResult {
        val usage = usageFor(command) ?: return DeliveryResult.Unsupported
        val (proxy, device) = connectedLink()
        sendLock.withLock {
            try {
                when (usage) {
                    is Usage.Key -> tap(proxy, device, REPORT_KEYBOARD, keyboardReport(usage.code, usage.shift), ByteArray(8))
                    is Usage.Media -> tap(proxy, device, REPORT_CONSUMER, consumerReport(usage.code), ByteArray(2))
                }
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission is missing", e)
            }
        }
        return DeliveryResult.Delivered("ok")
    }

    /** Types text as a US keyboard would. Characters without a key (accents, emoji) are skipped. */
    suspend fun sendText(text: String): DeliveryResult {
        val (proxy, device) = connectedLink()
        sendLock.withLock {
            try {
                for (ch in text) {
                    val key = keyFor(ch) ?: continue
                    tap(proxy, device, REPORT_KEYBOARD, keyboardReport(key.code, key.shift), ByteArray(8))
                }
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission is missing", e)
            }
        }
        return DeliveryResult.Delivered("ok")
    }

    // ---------------------------------------------------------------- reports

    private fun usageFor(command: RemoteCommand): Usage? = when (command) {
        RemoteCommand.UP -> Usage.Key(KEY_UP)
        RemoteCommand.DOWN -> Usage.Key(KEY_DOWN)
        RemoteCommand.LEFT -> Usage.Key(KEY_LEFT)
        RemoteCommand.RIGHT -> Usage.Key(KEY_RIGHT)
        RemoteCommand.OK -> Usage.Key(KEY_ENTER)
        RemoteCommand.BACK -> Usage.Media(MEDIA_BACK)
        RemoteCommand.HOME -> Usage.Media(MEDIA_HOME)
        RemoteCommand.VOLUME_UP -> Usage.Media(MEDIA_VOLUME_UP)
        RemoteCommand.VOLUME_DOWN -> Usage.Media(MEDIA_VOLUME_DOWN)
        RemoteCommand.MUTE -> Usage.Media(MEDIA_MUTE)
        RemoteCommand.REWIND -> Usage.Media(MEDIA_REWIND)
        RemoteCommand.FORWARD -> Usage.Media(MEDIA_FORWARD)
        RemoteCommand.POWER_OFF -> Usage.Media(MEDIA_POWER)
        RemoteCommand.SETTINGS -> null // no standard key for it
    }

    private fun keyFor(ch: Char): Usage.Key? {
        if (ch == '\u0000') return null
        val plain = UNSHIFTED.indexOf(ch)
        if (plain >= 0) return Usage.Key(0x04 + plain)
        val shifted = SHIFTED.indexOf(ch)
        if (shifted >= 0) return Usage.Key(0x04 + shifted, shift = true)
        return null
    }

    /** 8 bytes: modifiers, reserved, six key codes (only the first is used). */
    private fun keyboardReport(code: Int, shift: Boolean): ByteArray = ByteArray(8).also {
        it[0] = (if (shift) 0x02 else 0x00).toByte()
        it[2] = code.toByte()
    }

    /** 2 bytes: the 16-bit usage, low byte first. */
    private fun consumerReport(usage: Int): ByteArray =
        byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())

    /** Press, hold briefly, release. Without the release the TV would keep repeating the key. */
    private suspend fun tap(proxy: BluetoothHidDevice, device: BluetoothDevice, reportId: Int, down: ByteArray, up: ByteArray) {
        if (!proxy.sendReport(device, reportId, down)) throw IOException("Bluetooth did not accept the key press")
        delay(KEY_HOLD_MS)
        proxy.sendReport(device, reportId, up)
    }

    private fun connectedLink(): Pair<BluetoothHidDevice, BluetoothDevice> {
        val proxy = hid
        val address = connectedAddress
        val a = adapter
        if (proxy == null || address == null || a == null || state.value != ConnectionState.CONNECTED) {
            throw IOException("Bluetooth is not connected to the TV")
        }
        return proxy to a.getRemoteDevice(address)
    }

    private fun problem(message: String) {
        lastProblem = message
        Log.w(TAG, message)
    }

    private fun fail(message: String): Boolean {
        problem(message)
        state.value = ConnectionState.DISCONNECTED
        return false
    }
}
