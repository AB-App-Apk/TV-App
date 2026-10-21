package com.example.tvremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Bluetooth device the phone found while scanning, or already knows. */
data class NearbyDevice(
    val address: String,
    val name: String,
    /** Already paired with this phone. */
    val paired: Boolean,
    /** Pairing is in progress. */
    val pairing: Boolean,
    /** Its Bluetooth class says audio/video, which is what a TV usually reports. Only used to sort it first. */
    val looksLikeTv: Boolean
)

/**
 * Finds Bluetooth devices and pairs with the one the person picks. Pairing is the system's own secure pairing: the
 * phone and the TV show a code, the person confirms it on both, and the link is encrypted from then on.
 *
 * Call [start] when the setup screen opens and [stop] when it closes. Scanning and pairing only work between those.
 */
class BluetoothPairing(context: Context) {
    private companion object {
        const val TAG = "BluetoothPairing"
    }

    private val app: Context = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val known = LinkedHashMap<String, BluetoothDevice>()

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())

    /** Everything found so far, paired devices first. */
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** Why scanning or pairing did not work, in words for the screen. Null when there is nothing to report. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _paired = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** The address of a device that has just finished pairing. */
    val paired: SharedFlow<String> = _paired.asSharedFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    deviceIn(intent)?.let { remember(it) }
                    publish()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _scanning.value = false
                    publish()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondChanged(intent)
            }
        }
    }

    // ---------------------------------------------------------------- capability and permissions

    fun isBluetoothOn(): Boolean = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }

    /**
     * What Android must allow before scanning and pairing. Android 12 and newer groups these as "Nearby devices".
     * Android 11 and older only report scan results to apps that may see the phone's location.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts listening for scan results and pairing progress, and lists the devices that are already paired. */
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        loadPaired()
        publish()
    }

    /** Stops scanning and listening. */
    fun stop() {
        cancelScan()
        if (registered) {
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "receiver was not registered", e)
            }
            registered = false
        }
    }

    fun close() = stop()

    // ---------------------------------------------------------------- scanning and pairing

    /** Looks for devices for about 12 seconds. Results arrive in [devices]. */
    fun startScan() {
        val a = adapter ?: return fail("This phone has no Bluetooth.")
        if (!a.isEnabled) return fail("Bluetooth is turned off on the phone.")
        if (!hasPermissions()) return fail("Allow Bluetooth (Nearby devices) first.")
        _problem.value = null
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            loadPaired()
            val started = a.startDiscovery()
            _scanning.value = started
            if (!started) fail("The phone could not start scanning. Try again in a moment.")
        } catch (e: SecurityException) {
            fail("Allow Bluetooth (Nearby devices) first.")
        }
        publish()
    }

    fun cancelScan() {
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "cancelDiscovery failed", e)
        }
        _scanning.value = false
    }

    /**
     * Pairs with the device at [address]. A device that is already paired is reported at once. Otherwise the system
     * shows its pairing code and the result arrives on [paired], or in [problem] if it fails.
     */
    fun pair(address: String) {
        val device = synchronized(known) { known[address] } ?: return fail("That device is no longer in the list. Scan again.")
        try {
            cancelScan()
            _problem.value = null
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                _paired.tryEmit(address)
                return
            }
            if (!device.createBond()) fail("The phone could not start pairing. Try again.")
        } catch (e: SecurityException) {
            fail("Allow Bluetooth (Nearby devices) first.")
        }
        publish()
    }

    // ---------------------------------------------------------------- internals

    private fun remember(device: BluetoothDevice) {
        synchronized(known) { known[device.address] = device }
    }

    private fun loadPaired() {
        try {
            adapter?.bondedDevices.orEmpty().forEach { remember(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "could not list paired devices", e)
        }
    }

    private fun deviceIn(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private fun onBondChanged(intent: Intent) {
        val device = deviceIn(intent) ?: return
        remember(device)
        val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        val before = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
        when {
            now == BluetoothDevice.BOND_BONDED -> _paired.tryEmit(device.address)
            now == BluetoothDevice.BOND_NONE && before == BluetoothDevice.BOND_BONDING ->
                _problem.value = "Pairing did not finish. Check that the codes match on the TV and the phone, then try again."
        }
        publish()
    }

    private fun describe(device: BluetoothDevice): NearbyDevice? = try {
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        // A scanned device with no name is not something a person can pick, so it is left out.
        val name = device.name ?: if (bonded) device.address else null
        name?.let {
            NearbyDevice(
                address = device.address,
                name = it,
                paired = bonded,
                pairing = device.bondState == BluetoothDevice.BOND_BONDING,
                looksLikeTv = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
            )
        }
    } catch (e: SecurityException) {
        null
    }

    private fun publish() {
        val snapshot = synchronized(known) { known.values.toList() }
        _devices.value = snapshot.mapNotNull { describe(it) }
            .sortedWith(compareByDescending<NearbyDevice> { it.paired }.thenByDescending { it.looksLikeTv }.thenBy { it.name.lowercase() })
    }

    private fun fail(message: String) {
        _problem.value = message
        _scanning.value = false
    }
}
