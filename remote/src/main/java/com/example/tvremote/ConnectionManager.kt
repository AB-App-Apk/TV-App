package com.example.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * How well the remote can reach the TV right now.
 *
 * "Wi-Fi" here means TV Dash on the TV: reached over Wi-Fi, or over the Bluetooth data channel when Wi-Fi is down.
 * "Bluetooth" means the Bluetooth keyboard link (HID), which works even when TV Dash is not running.
 */
sealed class RemoteConnectionState {
    /** TV Dash answers and the Bluetooth keyboard link is up. */
    object FullyConnected : RemoteConnectionState()

    /** Only the Bluetooth keyboard link is up: the basic keys and typing work, TV Dash features do not. */
    object BluetoothOnly : RemoteConnectionState()

    /** Only TV Dash answers: every request works, but keys and typing do not go over the Bluetooth keyboard. */
    object WiFiOnly : RemoteConnectionState()

    /** Neither link is up. */
    object Disconnected : RemoteConnectionState()
}

/**
 * Layer 1: the router and state engine.
 *
 * It keeps both links alive, publishes [state], and decides which link carries each button and each piece of typing,
 * so the layers above never choose a transport.
 *
 * Buttons ([sendRemoteCommand]):
 *  - D-pad, OK, Back, volume, mute and power go over the Bluetooth keyboard by default (real key presses, no
 *    accessibility service needed on the TV). If that link is lost they are sent to TV Dash instead.
 *  - Home, Settings, rewind and forward go to TV Dash first and use the Bluetooth keyboard when TV Dash is down.
 *  - With no link at all, commands wait in a 5-second first-in-first-out buffer while both links are retried with
 *    growing delays. When a link returns they are sent in the order they were pressed; older ones are dropped.
 *
 * Typing ([sendKeyboardPayload]): the phone's keyboard keeps sending the same requests as always
 * ("text?t=<all text so far>&enter=0|1", and "text?done=1" when it closes). The manager reads them and:
 *  - state FullyConnected or BluetoothOnly: types the change on the Bluetooth keyboard, key by key, so it works in
 *    any app that accepts a keyboard, including YouTube's own search box;
 *  - state WiFiOnly, or text the Bluetooth keyboard has no key for (accents, emoji): forwards the request to TV
 *    Dash with its text untouched, which accepts any character;
 *  - state Disconnected: holds the newest request for 5 seconds and sends it when a link returns.
 * A typing session keeps the road it started on, so text is never typed twice.
 *
 * Call [start] when the screen appears and [stop] when it is destroyed.
 */
class ConnectionManager(
    context: Context,
    private val link: TvLink,
    private val keyboardLink: BluetoothHidTransport
) {
    private companion object {
        const val TAG = "ConnectionManager"

        /** A command or typed text waits this long for a link to come back; after that it is stale and dropped. */
        const val BUFFER_WINDOW_MS = 5_000L

        /** At most this many waiting commands, so a long outage cannot pile up a burst of key presses. */
        const val BUFFER_MAX = 10

        const val BACKOFF_MIN_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L

        /** While a link is healthy it is re-checked this often to notice a silent drop. */
        const val HEALTHY_CHECK_MS = 15_000L

        /** If the Bluetooth keyboard is not set up (no TV chosen, no permission), look again this often. */
        const val NOT_SET_UP_RECHECK_MS = 10_000L

        /** A typing session with no activity for this long is over; its text is never edited again. */
        const val SESSION_IDLE_MS = 10_000L

        /** Sent over the Bluetooth keyboard first. */
        val KEYBOARD_FIRST = setOf(
            RemoteCommand.UP, RemoteCommand.DOWN, RemoteCommand.LEFT, RemoteCommand.RIGHT, RemoteCommand.OK,
            RemoteCommand.BACK, RemoteCommand.VOLUME_UP, RemoteCommand.VOLUME_DOWN, RemoteCommand.MUTE,
            RemoteCommand.POWER_OFF
        )

        /**
         * The characters the Bluetooth keyboard can type: printable ASCII, the keys of a US keyboard. This must match
         * the table in [BluetoothHidTransport].
         */
        fun typable(c: Char): Boolean = c.code in 0x20..0x7E
    }

    private enum class Road { KEYBOARD, DASH }

    private enum class TypingRoad { NONE, KEYBOARD, DASH }

    private class Pending(val command: RemoteCommand, val queuedAt: Long)

    /** The phone's keyboard request, read: all text typed so far, and whether to submit or close. */
    private class KeyboardPayload(val text: String, val enter: Boolean, val done: Boolean)

    /** A keyboard request waiting for a link. Only the newest matters, because each carries all the text. */
    private class HeldPayload(val path: String, val queuedAt: Long)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Commands are sent one at a time, in order. Everything that touches [buffer] holds this lock. */
    private val sendLock = Mutex()
    private val buffer = ArrayDeque<Pending>()

    /** Typing requests are handled one at a time, in order. Everything below that touches the session holds this lock. */
    private val keyboardLock = Mutex()
    private var heldKeyboard: HeldPayload? = null

    /** Road the current typing session started on, or NONE when no session is open. */
    private var typingRoad = TypingRoad.NONE

    /** The text this session has put into the TV's text box, whichever road carried it. */
    private var sessionText = ""

    /** When typing moved from Bluetooth to TV Dash mid-session: the text already typed that TV Dash must leave alone. */
    private var imeBaseText = ""
    private var lastTypedAt = 0L

    @Volatile private var dashUp = false
    @Volatile private var keysUp = false
    @Volatile private var started = false

    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Disconnected)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** A network event wakes the TV Dash watcher early, so a returning Wi-Fi is noticed at once. */
    private val dashNudge = Channel<Unit>(Channel.CONFLATED)

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (started) return
        started = true
        keyboardLink.attach() // opens the Bluetooth HID profile; its service listener reports the link state
        watchNetwork()
        watchDash()
        watchKeyboard()
    }

    /** Unregisters the network callback, stops every background job, and closes the Bluetooth profile. */
    fun stop() {
        started = false
        networkCallback?.let { cb ->
            runCatching { appContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        scope.coroutineContext.cancelChildren()
        keyboardLink.release()
        dashUp = false
        keysUp = false
        _state.value = RemoteConnectionState.Disconnected
        scope.launch { sendLock.withLock { buffer.clear() } }
        scope.launch {
            keyboardLock.withLock {
                heldKeyboard = null
                resetSession()
            }
        }
    }

    // ---------------------------------------------------------------- buttons

    /**
     * Sends one button press. Returns Delivered with the TV's answer, Unsupported when no working link can carry
     * that button (for example Settings with only Bluetooth), or Offline when nothing is up. In the Offline case
     * the command is held for up to 5 seconds and goes out by itself if a link returns in time.
     */
    suspend fun sendRemoteCommand(command: RemoteCommand): DeliveryResult = sendLock.withLock {
        drainBufferLocked() // anything held earlier goes first, so the order of the presses is kept
        deliver(command) ?: run {
            hold(command)
            DeliveryResult.Offline
        }
    }

    /** Tries each road in order. Returns null when no link could carry the command. */
    private suspend fun deliver(command: RemoteCommand): DeliveryResult? {
        val roads = if (command in KEYBOARD_FIRST) listOf(Road.KEYBOARD, Road.DASH) else listOf(Road.DASH, Road.KEYBOARD)
        var unsupported = false
        for (road in roads) {
            if (road == Road.KEYBOARD) {
                if (!keysUp) continue
                try {
                    when (val result = keyboardLink.sendCommand(command)) {
                        is DeliveryResult.Delivered -> return result
                        DeliveryResult.Unsupported -> unsupported = true
                        DeliveryResult.Offline -> Log.w(TAG, "The Bluetooth keyboard could not send $command")
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "The Bluetooth keyboard link failed: ${e.message}")
                    setKeys(false)
                }
            } else {
                if (!dashUp) continue
                when (val reply = link.get(command.wifiPath)) {
                    is TvReply.Ok -> return DeliveryResult.Delivered(reply.body.trim())
                    // A refusal still means TV Dash answered; the screen explains the PIN problem.
                    is TvReply.Rejected -> return DeliveryResult.Delivered("rejected-${reply.http}")
                    TvReply.Unreachable -> {
                        setDash(false)
                        dashNudge.trySend(Unit)
                    }
                }
            }
        }
        return if (unsupported && (dashUp || keysUp)) DeliveryResult.Unsupported else null
    }

    // ---------------------------------------------------------------- the 5-second button buffer (callers hold sendLock)

    private fun hold(command: RemoteCommand) {
        purgeStale(SystemClock.elapsedRealtime())
        if (buffer.size >= BUFFER_MAX) buffer.removeFirst()
        buffer.addLast(Pending(command, SystemClock.elapsedRealtime()))
    }

    private fun purgeStale(now: Long) {
        while (buffer.isNotEmpty() && now - buffer.first().queuedAt > BUFFER_WINDOW_MS) buffer.removeFirst()
    }

    /** Sends what is waiting, oldest first. If a command still cannot go out it stays at the front, in order. */
    private suspend fun drainBufferLocked() {
        while (true) {
            purgeStale(SystemClock.elapsedRealtime())
            val next = buffer.removeFirstOrNull() ?: return
            if (deliver(next.command) == null) {
                buffer.addFirst(next)
                return
            }
        }
    }

    /** A link came back: send held buttons and held typing. */
    private fun flushSoon() {
        scope.launch { sendLock.withLock { drainBufferLocked() } }
        scope.launch { keyboardLock.withLock { drainHeldKeyboardLocked() } }
    }

    // ---------------------------------------------------------------- typing

    /**
     * Sends the phone keyboard's request. The request text is the same one the keyboard has always produced; the
     * manager decides how it reaches the TV (see the class comment). Requests that are not typing, such as a search
     * link, go to TV Dash untouched.
     *
     * Returns Ok when the text was typed or forwarded ("partial" if some characters had no key on the Bluetooth
     * keyboard, "queued" if no link is up and it is waiting), or the TV's refusal.
     */
    suspend fun sendKeyboardPayload(path: String): TvReply = keyboardLock.withLock {
        val payload = parseKeyboardPayload(path) ?: return@withLock postToDash(path)
        routeKeyboardLocked(path, payload, SystemClock.elapsedRealtime())
    }

    private fun parseKeyboardPayload(path: String): KeyboardPayload? {
        if (path != "text" && !path.startsWith("text?")) return null
        val uri = Uri.parse("http://tv/$path")
        return KeyboardPayload(
            text = uri.getQueryParameter("t") ?: "",
            enter = uri.getQueryParameter("enter") == "1",
            done = uri.getQueryParameter("done") == "1"
        )
    }

    /** One request to TV Dash, keeping the TV Dash link state up to date. */
    private suspend fun postToDash(path: String): TvReply {
        val reply = link.get(path)
        if (reply is TvReply.Unreachable) {
            setDash(false)
            dashNudge.trySend(Unit)
        } else {
            setDash(true)
        }
        return reply
    }

    private suspend fun routeKeyboardLocked(path: String, payload: KeyboardPayload, queuedAt: Long): TvReply {
        if (typingRoad != TypingRoad.NONE && SystemClock.elapsedRealtime() - lastTypedAt > SESSION_IDLE_MS) resetSession()
        if (payload.done) return finishSession(path)
        for (road in typingRoads(payload.text)) {
            val reply = if (road == TypingRoad.KEYBOARD) typeOverKeyboard(payload) else typeOverDash(path, payload)
            if (reply != null) return reply
        }
        // No link could take it: keep the newest request for 5 seconds.
        heldKeyboard = HeldPayload(path, queuedAt)
        return TvReply.Ok("queued")
    }

    /**
     * The roads to try, best first. A session already under way keeps its road while it is up. A new session follows
     * the connection state.
     */
    private fun typingRoads(text: String): List<TypingRoad> {
        val keyboardOk = keysUp && text.all { typable(it) }
        val dashOk = dashUp
        return when (typingRoad) {
            TypingRoad.KEYBOARD -> buildList {
                if (keyboardOk) add(TypingRoad.KEYBOARD)
                if (dashOk) add(TypingRoad.DASH)
            }
            TypingRoad.DASH -> buildList {
                if (dashOk) add(TypingRoad.DASH)
                if (keyboardOk) add(TypingRoad.KEYBOARD)
            }
            TypingRoad.NONE -> when (_state.value) {
                RemoteConnectionState.FullyConnected, RemoteConnectionState.BluetoothOnly -> buildList {
                    if (keyboardOk) add(TypingRoad.KEYBOARD)
                    if (dashOk) add(TypingRoad.DASH)
                    // Only Bluetooth is up and some characters have no key: type the others rather than nothing.
                    if (!keyboardOk && !dashOk && keysUp) add(TypingRoad.KEYBOARD)
                }
                RemoteConnectionState.WiFiOnly -> if (dashOk) listOf(TypingRoad.DASH) else emptyList()
                RemoteConnectionState.Disconnected -> emptyList()
            }
        }
    }

    /**
     * Types the change since the last request: backspaces for what was removed, then the new characters, then Enter
     * if the request submits. Returns null if the link failed, so the caller can try TV Dash.
     */
    private suspend fun typeOverKeyboard(payload: KeyboardPayload): TvReply? {
        val target = payload.text
        val common = commonPrefixLength(sessionText, target)
        val backspaces = sessionText.substring(common).count { typable(it) }
        val tail = target.substring(common)
        val typedTail = tail.filter { typable(it) }
        val keys = "\b".repeat(backspaces) + typedTail + (if (payload.enter) "\n" else "")
        try {
            if (keys.isNotEmpty()) keyboardLink.sendText(keys)
        } catch (e: IOException) {
            Log.w(TAG, "Typing over the Bluetooth keyboard failed: ${e.message}")
            setKeys(false)
            return null
        }
        if (payload.enter) resetSession() else rememberSession(target, TypingRoad.KEYBOARD)
        return TvReply.Ok(if (typedTail.length != tail.length) "partial" else "ok")
    }

    /**
     * Forwards the request to TV Dash. Its text is untouched, unless the session began on Bluetooth: then only the
     * part after what Bluetooth already typed is sent, in the same request format.
     */
    private suspend fun typeOverDash(path: String, payload: KeyboardPayload): TvReply? {
        if (typingRoad == TypingRoad.KEYBOARD) {
            imeBaseText = if (payload.text.startsWith(sessionText)) sessionText else ""
        }
        if (imeBaseText.isNotEmpty() && !payload.text.startsWith(imeBaseText)) {
            imeBaseText = "" // the user edited text that is already on the TV; it cannot be repaired from here
        }
        val request = if (imeBaseText.isEmpty()) {
            path
        } else {
            val tail = URLEncoder.encode(payload.text.removePrefix(imeBaseText), "UTF-8")
            "text?t=$tail&enter=${if (payload.enter) 1 else 0}"
        }
        val reply = link.get(request)
        if (reply is TvReply.Unreachable) {
            setDash(false)
            dashNudge.trySend(Unit)
            return null
        }
        setDash(true)
        if (payload.enter) resetSession() else rememberSession(payload.text, TypingRoad.DASH)
        return reply
    }

    /** The keyboard closed. TV Dash has an open composition to finish; the Bluetooth keyboard has nothing to close. */
    private suspend fun finishSession(path: String): TvReply {
        val road = typingRoad
        resetSession()
        heldKeyboard = null
        if (road == TypingRoad.KEYBOARD || !dashUp) return TvReply.Ok("ok")
        val reply = postToDash(path)
        return if (reply is TvReply.Unreachable) TvReply.Ok("ok") else reply
    }

    private fun rememberSession(text: String, road: TypingRoad) {
        sessionText = text
        typingRoad = road
        lastTypedAt = SystemClock.elapsedRealtime()
    }

    private fun resetSession() {
        typingRoad = TypingRoad.NONE
        sessionText = ""
        imeBaseText = ""
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    /** Sends the typing that was waiting, if it is still fresh. If there is still no link it is held again, with its original time. */
    private suspend fun drainHeldKeyboardLocked() {
        val held = heldKeyboard ?: return
        heldKeyboard = null
        if (SystemClock.elapsedRealtime() - held.queuedAt > BUFFER_WINDOW_MS) return
        val payload = parseKeyboardPayload(held.path) ?: return
        routeKeyboardLocked(held.path, payload, held.queuedAt)
    }

    // ---------------------------------------------------------------- state

    private fun setDash(up: Boolean) {
        if (dashUp != up) {
            dashUp = up
            publish()
        }
    }

    private fun setKeys(up: Boolean) {
        if (keysUp != up) {
            keysUp = up
            publish()
        }
    }

    private fun publish() {
        val next = when {
            dashUp && keysUp -> RemoteConnectionState.FullyConnected
            keysUp -> RemoteConnectionState.BluetoothOnly
            dashUp -> RemoteConnectionState.WiFiOnly
            else -> RemoteConnectionState.Disconnected
        }
        val previous = _state.getAndUpdate { next }
        if (previous === RemoteConnectionState.Disconnected && next !== RemoteConnectionState.Disconnected) flushSoon()
    }

    // ---------------------------------------------------------------- watchers (retry with exponential backoff)

    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { dashNudge.trySend(Unit) }
            override fun onLost(network: Network) { dashNudge.trySend(Unit) }
        }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            appContext.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the Wi-Fi network", e)
        }
    }

    /** Keeps asking TV Dash (Wi-Fi first, then the Bluetooth data channel) whether it answers. */
    private fun watchDash() {
        scope.launch {
            var wait = BACKOFF_MIN_MS
            while (isActive) {
                val up = link.get("ping") !is TvReply.Unreachable
                setDash(up)
                val pause = if (up) {
                    wait = BACKOFF_MIN_MS
                    HEALTHY_CHECK_MS
                } else {
                    val current = wait
                    wait = (wait * 2).coerceAtMost(BACKOFF_MAX_MS)
                    current
                }
                withTimeoutOrNull(pause) { dashNudge.receive() }
            }
        }
    }

    /** Keeps the Bluetooth keyboard link to the chosen TV open, reconnecting with growing delays when it drops. */
    private fun watchKeyboard() {
        scope.launch {
            var wait = BACKOFF_MIN_MS
            while (isActive) {
                val canTry = keyboardLink.isSupported() && keyboardLink.hasPermission() && !keyboardLink.targetAddress.isNullOrEmpty()
                if (!canTry) {
                    setKeys(false)
                    delay(NOT_SET_UP_RECHECK_MS)
                    continue
                }
                if (keyboardLink.connect()) {
                    wait = BACKOFF_MIN_MS
                    setKeys(true)
                    // Suspends while the link stays up; the HID service listener flips this state when it drops.
                    keyboardLink.getConnectionState().first { it != ConnectionState.CONNECTED }
                    setKeys(false)
                } else {
                    setKeys(false)
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(BACKOFF_MAX_MS)
                }
            }
        }
    }
}
