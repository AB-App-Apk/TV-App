package com.example.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** What the screen shows about the link to the TV. */
enum class TransportState(val label: String) {
    CONNECTING("Connecting..."),
    WIFI_ACTIVE("Connected via Wi-Fi"),
    FALLBACK_LEVERAGED("Connected via Bluetooth (fallback)"),
    OFFLINE("Offline"),
}

/**
 * Chooses how each button reaches the TV: Wi-Fi first, Bluetooth HID when Wi-Fi fails, and back to Wi-Fi as soon
 * as the TV answers again. The screen only calls [send] and watches [state]; it never needs to know which
 * transport carried the command.
 *
 * All changes of state happen under one lock, so a button press and a network event can never interleave.
 * Call [start] when the screen appears and [stop] when it is destroyed.
 */
class TransportManager(
    context: Context,
    private val wifi: RemoteTransport,
    private val bluetooth: BluetoothHidTransport
) {
    private companion object {
        const val TAG = "TransportManager"
        const val PROBE_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(TransportState.CONNECTING)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /** A sentence explaining why Bluetooth or Wi-Fi is not working, when there is one. */
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var probeJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (networkCallback != null) return
        bluetooth.attach()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { returnToWifi() } }
            override fun onLost(network: Network) { scope.launch { wifiLost() } }
        }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            appContext.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the Wi-Fi network", e)
        }
        scope.launch { bluetooth.getConnectionState().collect { onBluetoothState() } }
        scope.launch { initialConnect() }
    }

    /** Unregisters the network callback, stops every background job and closes the Bluetooth profile. */
    fun stop() {
        networkCallback?.let { cb ->
            runCatching { appContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        probeJob = null
        scope.coroutineContext.cancelChildren()
        bluetooth.release()
        _state.value = TransportState.CONNECTING
    }

    /** Called after the user allows Bluetooth or chooses a TV: try the fallback again right away. */
    fun retryBluetooth() {
        scope.launch {
            lock.withLock { if (_state.value != TransportState.WIFI_ACTIVE) enterFallback() }
        }
    }

    // ---------------------------------------------------------------- sending

    suspend fun send(command: RemoteCommand): DeliveryResult = lock.withLock {
        if (_state.value == TransportState.FALLBACK_LEVERAGED) sendOverBluetooth(command) else sendOverWifi(command)
    }

    private suspend fun sendOverWifi(command: RemoteCommand): DeliveryResult {
        // A second try only while Wi-Fi is believed to be fine; otherwise fail over quickly.
        val attempts = if (_state.value == TransportState.WIFI_ACTIVE) 2 else 1
        repeat(attempts) { attempt ->
            try {
                val result = wifi.sendCommand(command)
                markWifiActive()
                return result
            } catch (e: IOException) {
                Log.w(TAG, "Wi-Fi send failed (attempt ${attempt + 1})", e)
            }
        }
        enterFallback()
        return if (_state.value == TransportState.FALLBACK_LEVERAGED) sendOverBluetooth(command) else DeliveryResult.Offline
    }

    private suspend fun sendOverBluetooth(command: RemoteCommand): DeliveryResult = try {
        bluetooth.sendCommand(command)
    } catch (e: IOException) {
        Log.w(TAG, "Bluetooth send failed", e)
        _state.value = TransportState.OFFLINE
        _notice.value = "The Bluetooth link to the TV was lost."
        startProbe()
        DeliveryResult.Offline
    }

    // ---------------------------------------------------------------- transitions (callers hold the lock unless noted)

    private suspend fun initialConnect() {
        lock.withLock {
            if (wifi.connect()) markWifiActive() else enterFallback()
        }
    }

    /** Wi-Fi to Bluetooth: the TV could not be reached, so try the pre-paired Bluetooth link. */
    private suspend fun enterFallback() {
        _state.value = TransportState.CONNECTING
        if (bluetooth.connect()) {
            _state.value = TransportState.FALLBACK_LEVERAGED
            _notice.value = ""
        } else {
            _state.value = TransportState.OFFLINE
            _notice.value = bluetooth.lastProblem.orEmpty()
        }
        startProbe()
    }

    private suspend fun markWifiActive() {
        probeJob?.cancel()
        probeJob = null
        if (bluetooth.getConnectionState().value != ConnectionState.DISCONNECTED) bluetooth.disconnect()
        _state.value = TransportState.WIFI_ACTIVE
        _notice.value = ""
    }

    /** Network event (takes the lock itself): the phone lost its Wi-Fi network. */
    private suspend fun wifiLost() {
        lock.withLock {
            if (_state.value == TransportState.WIFI_ACTIVE || _state.value == TransportState.CONNECTING) enterFallback()
        }
    }

    /**
     * Bluetooth to Wi-Fi (takes the lock itself): a Wi-Fi network is available, or the periodic check ran. Only a
     * real answer from TV Dash counts. Then Bluetooth is closed and commands go back to Wi-Fi.
     */
    private suspend fun returnToWifi() {
        lock.withLock {
            if (_state.value == TransportState.WIFI_ACTIVE) return
            if (wifi.connect()) markWifiActive()
        }
    }

    /** Takes the lock itself: Bluetooth dropped while it was the active transport. */
    private suspend fun onBluetoothState() {
        lock.withLock {
            val dropped = bluetooth.getConnectionState().value == ConnectionState.DISCONNECTED
            if (dropped && _state.value == TransportState.FALLBACK_LEVERAGED) {
                _state.value = TransportState.OFFLINE
                _notice.value = "The Bluetooth link to the TV was lost."
                startProbe()
            }
        }
    }

    /** While on Bluetooth or offline, keep asking whether the TV answers over Wi-Fi again. */
    private fun startProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (isActive) {
                delay(PROBE_MS)
                returnToWifi()
            }
        }
    }
}
