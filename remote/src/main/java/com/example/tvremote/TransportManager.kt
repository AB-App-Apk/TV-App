package com.example.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
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
    BLUETOOTH_DATA("Connected via Bluetooth (full control)"),
    FALLBACK_LEVERAGED("Connected via Bluetooth keys only (fallback)"),
    OFFLINE("Offline"),
}

/**
 * Chooses how each button reaches the TV, best road first:
 *  1. Wi-Fi to TV Dash,
 *  2. the Bluetooth data channel to TV Dash (same requests, no Wi-Fi needed; chosen inside [link]),
 *  3. the Bluetooth keyboard link (basic keys only, works even when TV Dash is not running).
 * It returns to the best road as soon as the TV answers on it again. The screen only calls [send] and watches
 * [state]; it never needs to know which road carried a command.
 *
 * All changes of state happen under one lock, so a button press and a network event can never interleave.
 * Call [start] when the screen appears and [stop] when it is destroyed.
 */
class TransportManager(
    context: Context,
    private val link: TvLink,
    private val linked: RemoteTransport,
    private val bluetooth: BluetoothHidTransport
) {
    private companion object {
        const val TAG = "TransportManager"
        const val PROBE_MS = 5_000L

        /** TV Dash's answer when its accessibility service is off: it cannot press D-pad, OK or Back. */
        const val NO_ACCESSIBILITY = "no-a11y"

        /** After "no-a11y", keep sending those keys over the Bluetooth keyboard for this long before asking TV Dash again. */
        const val KEYS_RECHECK_MS = 30_000L

        /** If the Bluetooth keyboard link cannot be opened, wait this long before trying again. */
        const val KEYBOARD_RETRY_MS = 30_000L

        const val KEYBOARD_NOTE =
            "D-pad, OK and Back are using the Bluetooth keyboard because TV Dash's accessibility service is off."

        /** The keys that need TV Dash's accessibility service on the TV. */
        val KEY_COMMANDS = setOf(
            RemoteCommand.UP, RemoteCommand.DOWN, RemoteCommand.LEFT, RemoteCommand.RIGHT, RemoteCommand.OK, RemoteCommand.BACK
        )
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

    // Keys that need the TV's accessibility service: when it is off they are sent over the Bluetooth keyboard instead.
    private var a11yOff = false
    private var a11yCheckedAt = 0L
    private var keyboardActive = false
    private var keyboardRetryAt = 0L

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (networkCallback != null) return
        bluetooth.attach()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { recheck() } }
            override fun onLost(network: Network) { scope.launch { wifiLost() } }
        }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            appContext.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the Wi-Fi network", e)
        }
        scope.launch { bluetooth.getConnectionState().collect { onKeyLinkState() } }
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

    /** Called after the user allows Bluetooth or chooses a TV: look again right away. */
    fun retryBluetooth() {
        scope.launch {
            lock.withLock { if (_state.value != TransportState.WIFI_ACTIVE) reevaluate() }
        }
    }

    // ---------------------------------------------------------------- sending

    suspend fun send(command: RemoteCommand): DeliveryResult = lock.withLock {
        if (_state.value == TransportState.FALLBACK_LEVERAGED) sendOverKeys(command) else sendOverLink(command)
    }

    /** Roads 1 and 2: TV Dash answers, over Wi-Fi or over the Bluetooth data channel. */
    private suspend fun sendOverLink(command: RemoteCommand): DeliveryResult {
        // Accessibility was found off a moment ago: D-pad, OK and Back go straight over the Bluetooth keyboard.
        if (a11yOff && command in KEY_COMMANDS && SystemClock.elapsedRealtime() - a11yCheckedAt < KEYS_RECHECK_MS) {
            sendViaKeyboardLink(command)?.let { return it }
        }
        // A second try only while a road is believed to be fine; otherwise move on quickly.
        val attempts = if (_state.value == TransportState.WIFI_ACTIVE || _state.value == TransportState.BLUETOOTH_DATA) 2 else 1
        repeat(attempts) { attempt ->
            try {
                val result = linked.sendCommand(command)
                val refused = result is DeliveryResult.Delivered && result.reply == NO_ACCESSIBILITY
                if (command in KEY_COMMANDS && result is DeliveryResult.Delivered) {
                    if (refused) {
                        a11yOff = true
                        a11yCheckedAt = SystemClock.elapsedRealtime()
                    } else {
                        a11yOff = false // the key worked on the TV, so accessibility is back
                        keyboardActive = false
                    }
                }
                markLinkActive()
                if (refused && (command in KEY_COMMANDS || command == RemoteCommand.HOME)) {
                    sendViaKeyboardLink(command)?.let { return it }
                }
                return result
            } catch (e: IOException) {
                Log.w(TAG, "TV Dash did not answer (attempt ${attempt + 1})", e)
            }
        }
        enterFallback()
        return if (_state.value == TransportState.FALLBACK_LEVERAGED) sendOverKeys(command) else DeliveryResult.Offline
    }

    /**
     * Sends a key over the Bluetooth keyboard while TV Dash is still reachable, because its accessibility service is
     * off. Returns null when that link is not usable, so the caller keeps TV Dash's own answer.
     */
    private suspend fun sendViaKeyboardLink(command: RemoteCommand): DeliveryResult? {
        if (bluetooth.getConnectionState().value != ConnectionState.CONNECTED) {
            if (SystemClock.elapsedRealtime() < keyboardRetryAt) return null
            if (!bluetooth.connect()) {
                keyboardRetryAt = SystemClock.elapsedRealtime() + KEYBOARD_RETRY_MS
                return null
            }
        }
        return try {
            bluetooth.sendCommand(command).also {
                if (it is DeliveryResult.Delivered) {
                    keyboardActive = true
                    _notice.value = KEYBOARD_NOTE
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "The Bluetooth keyboard could not send the key", e)
            keyboardActive = false
            null
        }
    }

    /** Road 3: plain keyboard keys over the Bluetooth HID link. */
    private suspend fun sendOverKeys(command: RemoteCommand): DeliveryResult = try {
        bluetooth.sendCommand(command)
    } catch (e: IOException) {
        Log.w(TAG, "Bluetooth keys failed", e)
        _state.value = TransportState.OFFLINE
        _notice.value = "The Bluetooth link to the TV was lost."
        startProbe()
        DeliveryResult.Offline
    }

    // ---------------------------------------------------------------- transitions (callers hold the lock unless noted)

    private suspend fun initialConnect() {
        lock.withLock { reevaluate() }
    }

    private suspend fun reevaluate() {
        if (linked.connect()) markLinkActive() else enterFallback()
    }

    /** TV Dash answered. Record on which road, drop the keyboard link, and keep watching for Wi-Fi if it was Bluetooth. */
    private suspend fun markLinkActive() {
        if (!keyboardActive && bluetooth.getConnectionState().value != ConnectionState.DISCONNECTED) bluetooth.disconnect()
        _notice.value = if (keyboardActive) KEYBOARD_NOTE else ""
        if (link.carrier.value == Carrier.WIFI) {
            probeJob?.cancel()
            probeJob = null
            _state.value = TransportState.WIFI_ACTIVE
        } else {
            _state.value = TransportState.BLUETOOTH_DATA
            if (probeJob?.isActive != true) startProbe()
        }
    }

    /** Neither road to TV Dash works: try the pre-paired Bluetooth keyboard link. */
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

    /** Network event (takes the lock itself): the phone lost its Wi-Fi network. */
    private suspend fun wifiLost() {
        lock.withLock {
            if (_state.value == TransportState.WIFI_ACTIVE || _state.value == TransportState.CONNECTING) reevaluate()
        }
    }

    /**
     * Periodic check or a new network (takes the lock itself). Only a real answer from TV Dash counts. When it
     * comes, the state moves to the best road that answered and the keyboard link is closed.
     */
    private suspend fun recheck() {
        lock.withLock {
            if (_state.value == TransportState.WIFI_ACTIVE) return
            if (linked.connect()) markLinkActive()
        }
    }

    /** Takes the lock itself: the Bluetooth keyboard link dropped while it was the active road. */
    private suspend fun onKeyLinkState() {
        lock.withLock {
            val dropped = bluetooth.getConnectionState().value == ConnectionState.DISCONNECTED
            if (dropped && _state.value == TransportState.FALLBACK_LEVERAGED) {
                _state.value = TransportState.OFFLINE
                _notice.value = "The Bluetooth link to the TV was lost."
                startProbe()
            }
        }
    }

    /** While not on Wi-Fi, keep asking whether a better road answers. */
    private fun startProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (isActive) {
                delay(PROBE_MS)
                recheck()
            }
        }
    }
}
