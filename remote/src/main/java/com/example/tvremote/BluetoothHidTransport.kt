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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Bluetooth fallback: the phone registers as a Bluetooth keyboard with media keys (the classic HID Device
 * profile, Android 9+), and the TV, which must already be paired, treats it like any keyboard. No TV Dash needed.
 *
 * Arrow keys, Enter and typing are keyboard keys (Generic Desktop / Keyboard collection, keys on the Keyboard page).
 * Consumer Control (usage page 0x0C) has no D-pad, so it carries Home, Back, volume, power, the media keys and All Apps.
 * A third collection, System Control (report 3), carries Wake Up, so Power On can wake a TV without ever being able
 * to switch it off. The three collections share one descriptor, told apart by report id. A TV remembers the descriptor
 * it saw when the phone was paired: after this one changed, the phone must be forgotten on the TV and on the phone and
 * paired again, or the TV ignores report 3.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is guarded by hasPermission() or catches SecurityException.
class BluetoothHidTransport(context: Context) : RemoteTransport {
    companion object {
        private const val TAG = "BluetoothHid"
        private const val PREF_TARGET = "bt_target"
        private const val REPORT_KEYBOARD = 1
        private const val REPORT_CONSUMER = 2
        private const val REPORT_SYSTEM = 3

        // Timing. A key is never pressed and released in the same instant, and the TV always sees a modifier
        // (Shift, Meta) change before the key it applies to.
        /** How long a key stays down. Inside the 15-25 ms window the TV's keyboard handling needs, and far below auto-repeat. */
        private const val KEY_HOLD_MS = 20L

        /** Pause after a modifier changes, so the TV registers it before the next report. */
        private const val MODIFIER_SETTLE_MS = 10L

        /** Pause after a release, so the same key pressed again counts as a new press. */
        private const val KEY_GAP_MS = 12L

        /** The TV opens its input channel just after it reports the link up; wait before the first report. */
        private const val CHANNEL_SETTLE_MS = 300L

        // Modifier byte (byte 0 of the keyboard report)
        private const val MOD_LEFT_SHIFT = 0x02
        private const val MOD_LEFT_META = 0x08
        private const val KEY_I = 0x0C

        // System Control report (Generic Desktop page): one byte, bit 0 Power Down, bit 1 Sleep, bit 2 Wake Up.
        // Wake Up becomes Android's KEYCODE_WAKEUP, which wakes a sleeping TV and does nothing to one that is awake.
        private const val SYSTEM_WAKE_UP = 0x04
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
        private const val MEDIA_ALL_APPS = 0x1A2 // Android maps this usage to KEYCODE_ALL_APPS

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
            0xC0,                   // End Collection
            // --- System control: Power Down, Sleep, Wake Up as three bits (report 3) ---
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x80,             // Usage (System Control)
            0xA1, 0x01,             // Collection (Application)
            0x85, REPORT_SYSTEM,    //   Report ID
            0x19, 0x81, 0x29, 0x83, //   Usage Minimum/Maximum (System Power Down .. System Wake Up)
            0x15, 0x00, 0x25, 0x01, //   Logical Minimum 0, Maximum 1
            0x75, 0x01, 0x95, 0x03, //   3 fields of 1 bit
            0x81, 0x02,             //   Input (Data, Variable, Absolute)
            0x95, 0x05,             //   5 bits of padding
            0x81, 0x01,             //   Input (Constant)
            0xC0                    // End Collection
        )
    }

    private sealed interface Usage {
        /** A key on the keyboard page, with the modifier byte that must be down while it is pressed. */
        data class Key(val code: Int, val modifiers: Int = 0) : Usage
        data class Media(val code: Int) : Usage

        /** A System Control key: [mask] is the bit (or bits) to set in the one-byte report. */
        data class SystemKey(val mask: Int) : Usage
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

    /** True when the TV switched this keyboard to boot mode, whose reports have no report id and no media keys. */
    @Volatile private var bootProtocol = false

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
                        bootProtocol = false
                        state.value = ConnectionState.DISCONNECTED
                    }
                }
            }

            /** A host may ask for a report. An unanswered request can make it treat the keyboard as faulty. */
            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                val proxy = hid ?: return
                val data = when (id.toInt()) {
                    REPORT_KEYBOARD -> ByteArray(8)
                    REPORT_CONSUMER -> ByteArray(2)
                    REPORT_SYSTEM -> ByteArray(1)
                    0 -> if (bootProtocol) ByteArray(8) else null
                    else -> null
                }
                try {
                    if (data != null) {
                        proxy.replyReport(device, type, id, data)
                    } else {
                        proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission to answer the TV's report request", e)
                }
            }

            /** The host may set an output report (keyboard lights). There is nothing to do, but it must be answered. */
            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                try {
                    hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission to answer the TV's report request", e)
                }
            }

            /** Boot mode reports are plain 8-byte keyboard reports with no report id. */
            override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
                bootProtocol = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
                Log.i(TAG, "The TV chose ${if (bootProtocol) "boot" else "report"} mode")
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                connectedAddress = null
                bootProtocol = false
                state.value = ConnectionState.DISCONNECTED
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
        bootProtocol = false
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
        if (up == null) return fail("The TV did not answer over Bluetooth within 5 seconds. Is it on?")
        // The TV opens its input channel just after it reports the link up. Wait, then tell it no key is down.
        delay(CHANNEL_SETTLE_MS)
        hid?.let { releaseKeyboard(it, device) }
        return true
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
                    is Usage.Key -> typeKeys(proxy, device, listOf(usage))
                    is Usage.Media -> pressConsumer(proxy, device, usage.code)
                    is Usage.SystemKey -> pressSystem(proxy, device, usage.mask)
                }
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission is missing", e)
            }
        }
        return DeliveryResult.Delivered("ok")
    }

    /** Types text as a US keyboard would. Characters without a key (accents, emoji) are skipped. */
    suspend fun sendText(text: String): DeliveryResult {
        val keys = text.mapNotNull { keyFor(it) }
        val (proxy, device) = connectedLink()
        sendLock.withLock {
            try {
                typeKeys(proxy, device, keys)
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission is missing", e)
            }
        }
        return DeliveryResult.Delivered("ok")
    }

    // ---------------------------------------------------------------- key mapping

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
        // Power On must never switch a TV off. The power key is a toggle, so On uses System Wake Up instead: it wakes
        // a sleeping TV and does nothing to one that is already awake.
        RemoteCommand.POWER_ON -> Usage.SystemKey(SYSTEM_WAKE_UP)
        RemoteCommand.APPS -> Usage.Media(MEDIA_ALL_APPS)
        // Android's key layout has no key for Settings, so this is the Meta+I shortcut. Newer Android versions open
        // Settings with it; an Android 14 TV may ignore it. TV Dash is the reliable way to open Settings.
        RemoteCommand.SETTINGS -> Usage.Key(KEY_I, MOD_LEFT_META)
    }

    /** Printable ASCII, backspace, enter, tab, escape. Capitals and symbols are the base key with Left Shift down. */
    private fun keyFor(ch: Char): Usage.Key? {
        if (ch == '\u0000') return null
        val plain = UNSHIFTED.indexOf(ch)
        if (plain >= 0) return Usage.Key(0x04 + plain)
        val shifted = SHIFTED.indexOf(ch)
        if (shifted >= 0) return Usage.Key(0x04 + shifted, MOD_LEFT_SHIFT)
        return null
    }

    // ---------------------------------------------------------------- reports (the part the TV's keyboard handling is picky about)

    /** The keyboard report id: 1 normally, 0 when the TV switched to boot mode (boot reports carry no id). */
    private fun keyboardId(): Int = if (bootProtocol) 0 else REPORT_KEYBOARD

    /** 8 bytes: byte 0 modifiers, byte 1 reserved, bytes 2-7 key codes (only the first is used). */
    private fun keyboardReport(modifiers: Int, code: Int): ByteArray = ByteArray(8).also {
        it[0] = modifiers.toByte()
        it[2] = code.toByte()
    }

    /** 2 bytes: the 16-bit usage, low byte first. */
    private fun consumerReport(usage: Int): ByteArray =
        byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())

    private fun sendKeyboard(proxy: BluetoothHidDevice, device: BluetoothDevice, modifiers: Int, code: Int) {
        if (!proxy.sendReport(device, keyboardId(), keyboardReport(modifiers, code))) {
            throw IOException("Bluetooth did not accept a keyboard report")
        }
    }

    /**
     * Presses the keys one after another with the modifier state kept exact: a modifier goes down alone and settles
     * before the key it applies to, stays down across consecutive keys that need it, and comes up after the last. Each
     * key is down for [KEY_HOLD_MS] and released in its own report. Whatever happens, even cancellation, a final
     * all-up report goes out so nothing stays pressed on the TV.
     */
    private suspend fun typeKeys(proxy: BluetoothHidDevice, device: BluetoothDevice, keys: List<Usage.Key>) {
        var held = 0
        try {
            for (key in keys) {
                if (key.modifiers != held) {
                    sendKeyboard(proxy, device, key.modifiers, 0) // modifiers change first, on their own
                    held = key.modifiers
                    delay(MODIFIER_SETTLE_MS)
                }
                sendKeyboard(proxy, device, held, key.code) // key down, modifiers still held
                delay(KEY_HOLD_MS)
                sendKeyboard(proxy, device, held, 0) // key up
                delay(KEY_GAP_MS)
            }
        } finally {
            withContext(NonCancellable) { releaseKeyboard(proxy, device) }
        }
    }

    /** A media key: down for [KEY_HOLD_MS], then released. In boot mode the TV has no media keys. */
    private suspend fun pressConsumer(proxy: BluetoothHidDevice, device: BluetoothDevice, usage: Int) {
        if (bootProtocol) throw IOException("The TV put the keyboard in boot mode, which has no media keys")
        try {
            if (!proxy.sendReport(device, REPORT_CONSUMER, consumerReport(usage))) {
                throw IOException("Bluetooth did not accept the media key")
            }
            delay(KEY_HOLD_MS)
        } finally {
            withContext(NonCancellable) { releaseConsumer(proxy, device) }
        }
        delay(KEY_GAP_MS)
    }

    /** A System Control key (wake up): down for [KEY_HOLD_MS], then released. In boot mode the TV has no system keys. */
    private suspend fun pressSystem(proxy: BluetoothHidDevice, device: BluetoothDevice, mask: Int) {
        if (bootProtocol) throw IOException("The TV put the keyboard in boot mode, which has no system keys")
        try {
            if (!proxy.sendReport(device, REPORT_SYSTEM, byteArrayOf(mask.toByte()))) {
                throw IOException("Bluetooth did not accept the wake-up key")
            }
            delay(KEY_HOLD_MS)
        } finally {
            withContext(NonCancellable) { releaseSystem(proxy, device) }
        }
        delay(KEY_GAP_MS)
    }

    private suspend fun releaseSystem(proxy: BluetoothHidDevice, device: BluetoothDevice) {
        repeat(2) { attempt ->
            try {
                if (proxy.sendReport(device, REPORT_SYSTEM, ByteArray(1))) return
            } catch (e: SecurityException) {
                return
            }
            if (attempt == 0) delay(KEY_GAP_MS)
        }
        Log.w(TAG, "The TV did not accept the system key release")
    }

    /** All keyboard keys and modifiers up. Tried twice, because a release that is lost leaves a key stuck down. */
    private suspend fun releaseKeyboard(proxy: BluetoothHidDevice, device: BluetoothDevice) {
        repeat(2) { attempt ->
            try {
                if (proxy.sendReport(device, keyboardId(), ByteArray(8))) return
            } catch (e: SecurityException) {
                return
            }
            if (attempt == 0) delay(KEY_GAP_MS)
        }
        Log.w(TAG, "The TV did not accept the key release")
    }

    private suspend fun releaseConsumer(proxy: BluetoothHidDevice, device: BluetoothDevice) {
        repeat(2) { attempt ->
            try {
                if (proxy.sendReport(device, REPORT_CONSUMER, ByteArray(2))) return
            } catch (e: SecurityException) {
                return
            }
            if (attempt == 0) delay(KEY_GAP_MS)
        }
        Log.w(TAG, "The TV did not accept the media key release")
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
