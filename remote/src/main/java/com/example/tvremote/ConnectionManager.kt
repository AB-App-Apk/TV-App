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
import java.text.Normalizer

/**
 * The only words Layer 1 returns about how a request went. None of them is a prompt: the layers above decide
 * whether anything is shown. TV Dash's own error codes ("no-a11y", "no-overlay", "no-keyboard", "no-field") and a
 * refused PIN never leave Layer 1; they are turned into a fallback or into [UNAVAILABLE].
 */
object RemoteStatus {
    /** It was carried out (sent to the TV by a link that accepted it). */
    const val OK = "ok"

    /** Typed, but some characters were replaced by plain letters or had no key at all. */
    const val PARTIAL = "partial"

    /** No link is up. It is held for up to 5 seconds and goes out by itself if a link returns. */
    const val QUEUED = "queued"

    /** A link is up, but none of the roads available now can do this request (for example a TV Dash feature while only Bluetooth is up). */
    const val UNAVAILABLE = "unavailable"

    /** Power On only: no link is up, so the phone itself has to wake the TV (infrared, Wake-on-LAN). The screen runs that. */
    const val WAKE_NEEDED = "wake"

    /** Apps only: TV Dash answers, so the phone shows its own list of the TV's apps. */
    const val LIST = "list"
}

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
 * so the layers above never choose a transport and never see an infrastructure error. Every request ends in one of
 * the plain words in [RemoteStatus].
 *
 * Buttons ([sendRemoteCommand]):
 *  - D-pad, OK, Back, volume, mute, power, rewind and forward are real keys, so they go over the Bluetooth keyboard
 *    first: it needs neither TV Dash nor its accessibility service. If that link is down they go to TV Dash.
 *  - Home and Apps go to TV Dash first. If TV Dash is closed, or answers that it is blocked (accessibility or
 *    "Display over other apps" off), they are sent as the standard Home key and the standard All Apps key.
 *  - Power On is decided by the state: with TV Dash answering (WiFiOnly, FullyConnected) it wakes the screen through TV
 *    Dash; with only Bluetooth up it sends the Wake Up key silently, with no network step and no message. Wake Up
 *    only wakes a sleeping TV and does nothing to one that is awake, so On can never switch the TV off. With no link
 *    at all it answers [RemoteStatus.WAKE_NEEDED] and does not hold the press, because nothing is listening.
 *  - Apps ([openApps]) is decided by the state too: TV Dash answering means the phone shows its own list of the TV's
 *    apps; with only Bluetooth up it skips TV Dash and sends the All Apps key (Android's KEYCODE_ALL_APPS, 284).
 *  - Settings has no standard key (Android's generic key layout maps nothing to Settings). Over Bluetooth it is sent
 *    as the Meta+I shortcut, which newer Android versions use for Settings and an Android 14 TV may ignore, so TV Dash
 *    stays the reliable way. [hint] says so whenever a shortcut stood in for Settings or Apps.
 *  - With no link at all, buttons wait in a 5-second first-in-first-out buffer while both links are retried with
 *    growing delays. When a link returns they are sent in the order pressed; older ones are dropped.
 *
 * Typing ([sendKeyboardPayload]): the phone's keyboard keeps sending the same requests as always
 * ("text?t=<all text so far>&enter=0|1", and "text?done=1" when it closes). The manager reads them and:
 *  - when the text is plain keyboard characters and the Bluetooth keyboard is up, types the change as key presses
 *    and backspaces, which works in any app, including YouTube's own search box;
 *  - when the text has other characters (accents, emoji) it prefers TV Dash, which types any character, and uses the
 *    Bluetooth keyboard only if TV Dash cannot (accents become plain letters, so é is typed as e; the rest is skipped);
 *  - when TV Dash is closed, blocked, or has no text box to type in, the Bluetooth keyboard takes over; and the
 *    other way round if the Bluetooth keyboard fails;
 *  - with no link at all, holds the newest request for 5 seconds.
 * A typing session keeps its road, so text is never typed twice.
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

        /** After TV Dash cannot take typing (no keyboard set, no text box), do not ask it again for this long. */
        const val DASH_TYPING_PAUSE_MS = 10_000L

        /** Sent over the Bluetooth keyboard first. */
        val KEYBOARD_FIRST = setOf(
            RemoteCommand.UP, RemoteCommand.DOWN, RemoteCommand.LEFT, RemoteCommand.RIGHT, RemoteCommand.OK,
            RemoteCommand.BACK, RemoteCommand.VOLUME_UP, RemoteCommand.VOLUME_DOWN, RemoteCommand.MUTE,
            RemoteCommand.POWER_OFF, RemoteCommand.REWIND, RemoteCommand.FORWARD
        )

        /** TV Dash answers meaning "I am open, but I cannot do that button". */
        val BLOCKED_COMMAND = setOf("no-a11y", "no-overlay")

        /** TV Dash answers meaning "I am open, but I cannot type that". */
        val BLOCKED_TYPING = setOf("no-keyboard", "no-field", "no-a11y", "no-overlay")

        /**
         * The characters the Bluetooth keyboard can type: printable ASCII, the keys of a US keyboard. This must match
         * the table in [BluetoothHidTransport].
         */
        fun typable(c: Char): Boolean = c.code in 0x20..0x7E

        /** Common characters that have a close stand-in on a US keyboard. */
        private val STAND_INS = mapOf(
            'ß' to "ss", 'æ' to "ae", 'Æ' to "AE", 'œ' to "oe", 'Œ' to "OE", 'ø' to "o", 'Ø' to "O",
            'đ' to "d", 'Đ' to "D", 'ł' to "l", 'Ł' to "L", 'ı' to "i",
            '\u2018' to "'", '\u2019' to "'", '\u201C' to "\"", '\u201D' to "\"",
            '\u2013' to "-", '\u2014' to "-", '\u2026' to "...", '\u00A0' to " "
        )

        /** The keys to press for one character: itself, a plain-letter stand-in (é becomes e), or nothing at all. */
        fun keysFor(c: Char): String {
            if (typable(c)) return c.toString()
            STAND_INS[c]?.let { return it }
            return Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { typable(it) }
        }
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
    private var dashTypingPausedUntil = 0L

    @Volatile private var dashUp = false
    @Volatile private var keysUp = false
    @Volatile private var started = false

    /** True while [hint] holds the reason the Bluetooth keyboard could not connect, so only that hint is cleared later. */
    @Volatile private var keyboardHintShown = false

    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Disconnected)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    private val _hint = MutableStateFlow("")

    /**
     * A plain sentence about why something is not working, for a status line if a layer above wants one. The manager
     * never shows anything itself, and it is safe to ignore. Empty when there is nothing to say.
     */
    val hint: StateFlow<String> = _hint.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** A network event wakes the TV Dash watcher early, so a returning Wi-Fi is noticed at once. */
    private val dashNudge = Channel<Unit>(Channel.CONFLATED)

    /** Wakes the Bluetooth keyboard watcher early, for example right after the user chooses the TV. */
    private val keyboardNudge = Channel<Unit>(Channel.CONFLATED)

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
        _hint.value = ""
        scope.launch { sendLock.withLock { buffer.clear() } }
        scope.launch {
            keyboardLock.withLock {
                heldKeyboard = null
                resetSession()
            }
        }
    }

    /** Call after the user allows Bluetooth or chooses the TV: look for the Bluetooth keyboard link right now. */
    fun retryKeyboard() {
        keyboardNudge.trySend(Unit)
    }

    /** Call after the TV's address or PIN was saved, or the Bluetooth TV was chosen: check both links now instead of waiting out the delay. */
    fun retryNow() {
        dashNudge.trySend(Unit)
        keyboardNudge.trySend(Unit)
    }

    // ---------------------------------------------------------------- buttons

    /**
     * Sends one button press and always returns Delivered with a [RemoteStatus]: OK, UNAVAILABLE, or QUEUED. Nothing
     * is thrown and no error code from the TV is passed on.
     */
    suspend fun sendRemoteCommand(command: RemoteCommand): DeliveryResult = sendLock.withLock {
        // Power On with no link at all: nothing is listening, so holding the press for 5 seconds would be pointless.
        // The phone's own wake-up (infrared, Wake-on-LAN) is all that is left; the screen runs it.
        if (command == RemoteCommand.POWER_ON && _state.value === RemoteConnectionState.Disconnected) {
            return@withLock DeliveryResult.Delivered(RemoteStatus.WAKE_NEEDED)
        }
        drainBufferLocked() // anything held earlier goes first, so the order of the presses is kept
        deliver(command) ?: run {
            hold(command)
            DeliveryResult.Delivered(RemoteStatus.QUEUED)
        }
    }

    /**
     * The Apps button, decided by the connection state. TV Dash answering (WiFiOnly, FullyConnected) returns
     * [RemoteStatus.LIST]: the phone shows its own list of the TV's apps. Only Bluetooth up (BluetoothOnly) skips TV
     * Dash and sends the All Apps key over the Bluetooth keyboard, silently. No link (Disconnected) holds that key for
     * 5 seconds like any other button.
     */
    suspend fun openApps(): DeliveryResult = when (_state.value) {
        RemoteConnectionState.FullyConnected, RemoteConnectionState.WiFiOnly -> DeliveryResult.Delivered(RemoteStatus.LIST)
        RemoteConnectionState.BluetoothOnly, RemoteConnectionState.Disconnected -> sendRemoteCommand(RemoteCommand.APPS)
    }

    /**
     * Tries each road in order: the Bluetooth keyboard first for real keys, TV Dash first for Home, Apps and Power On. A road
     * that is down, refuses the button, or answers that TV Dash is blocked is skipped silently.
     * Returns null when no link is up at all, so the caller holds the command.
     */
    private suspend fun deliver(command: RemoteCommand): DeliveryResult? {
        val roads = if (command in KEYBOARD_FIRST) listOf(Road.KEYBOARD, Road.DASH) else listOf(Road.DASH, Road.KEYBOARD)
        for (road in roads) {
            if (road == Road.KEYBOARD) {
                if (!keysUp) continue
                try {
                    // Unsupported means the standard key layout has no key for this button (Settings): try TV Dash.
                    if (keyboardLink.sendCommand(command) is DeliveryResult.Delivered) {
                        if (command == RemoteCommand.SETTINGS || command == RemoteCommand.APPS) noteShortcutUsed(command)
                        if (command == RemoteCommand.POWER_ON) {
                            _hint.value = "Over Bluetooth, On sends the Wake Up key: it wakes a sleeping TV and does nothing to a TV that is on. " +
                                "If it never wakes the TV, forget the phone on the TV and pair it again."
                        }
                        return DeliveryResult.Delivered(RemoteStatus.OK)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "The Bluetooth keyboard link failed: ${e.message}")
                    setKeys(false)
                }
            } else {
                if (!dashUp) continue
                when (val reply = link.get(command.wifiPath)) {
                    is TvReply.Ok -> {
                        if (reply.body.trim() in BLOCKED_COMMAND) {
                            noteDashBlocked()
                        } else {
                            _hint.value = ""
                            return DeliveryResult.Delivered(RemoteStatus.OK)
                        }
                    }
                    is TvReply.Rejected -> noteDashRejected(reply.http)
                    TvReply.Unreachable -> {
                        setDash(false)
                        dashNudge.trySend(Unit)
                    }
                }
            }
        }
        return if (dashUp || keysUp) DeliveryResult.Delivered(RemoteStatus.UNAVAILABLE) else null
    }

    private fun noteDashBlocked() {
        _hint.value = if (keysUp) {
            "TV Dash's accessibility service is off on the TV, so those buttons use the Bluetooth keyboard."
        } else {
            "TV Dash's accessibility service is off on the TV. Turn it on, or set up the Bluetooth keyboard."
        }
    }

    private fun noteShortcutUsed(command: RemoteCommand) {
        val what = if (command == RemoteCommand.SETTINGS) "Settings sends the Meta+I shortcut" else "Apps sends the All Apps key"
        _hint.value = "Over Bluetooth, $what. Some TVs ignore it; TV Dash opens it reliably."
    }

    private fun noteDashRejected(http: Int) {
        _hint.value = if (http == 403) "The TV rejected the PIN. Check it under Change TV." else "TV Dash sent an unexpected answer ($http)."
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
     * link, go to TV Dash untouched and their answer is passed on as it is.
     *
     * Typing always returns Ok with a [RemoteStatus]: OK, PARTIAL, QUEUED or UNAVAILABLE.
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
        if (dashUp || keysUp) return TvReply.Ok(RemoteStatus.UNAVAILABLE)
        // No link at all: keep the newest request for 5 seconds.
        heldKeyboard = HeldPayload(path, queuedAt)
        return TvReply.Ok(RemoteStatus.QUEUED)
    }

    /**
     * The roads to try, best first. A session already under way keeps its road while it is up. A new session follows
     * the connection state. Plain keyboard characters prefer the Bluetooth keyboard; anything else prefers TV Dash.
     */
    private fun typingRoads(text: String): List<TypingRoad> {
        val exact = text.all { typable(it) }
        val keyboardFirst = listOf(TypingRoad.KEYBOARD, TypingRoad.DASH)
        val dashFirst = listOf(TypingRoad.DASH, TypingRoad.KEYBOARD)
        val order = when (typingRoad) {
            TypingRoad.KEYBOARD -> if (exact) keyboardFirst else dashFirst
            TypingRoad.DASH -> dashFirst
            TypingRoad.NONE -> when (_state.value) {
                RemoteConnectionState.FullyConnected, RemoteConnectionState.BluetoothOnly -> if (exact) keyboardFirst else dashFirst
                RemoteConnectionState.WiFiOnly -> listOf(TypingRoad.DASH)
                RemoteConnectionState.Disconnected -> emptyList()
            }
        }
        val dashOk = dashUp && SystemClock.elapsedRealtime() >= dashTypingPausedUntil
        return order.filter { if (it == TypingRoad.KEYBOARD) keysUp else dashOk }
    }

    /**
     * Types the change since the last request: backspaces for what was removed, then the new characters, then Enter
     * if the request submits. Characters with no key become their plain-letter stand-in or are skipped. Returns null
     * if the link failed, so the caller can try TV Dash.
     */
    private suspend fun typeOverKeyboard(payload: KeyboardPayload): TvReply? {
        val target = payload.text
        val common = commonPrefixLength(sessionText, target)
        val backspaces = sessionText.substring(common).sumOf { keysFor(it).length }
        val tail = target.substring(common)
        val typedTail = tail.map { keysFor(it) }.joinToString("")
        val keys = "\b".repeat(backspaces) + typedTail + (if (payload.enter) "\n" else "")
        try {
            if (keys.isNotEmpty()) keyboardLink.sendText(keys)
        } catch (e: IOException) {
            Log.w(TAG, "Typing over the Bluetooth keyboard failed: ${e.message}")
            setKeys(false)
            return null
        }
        val exact = tail.all { typable(it) }
        if (payload.enter) resetSession() else rememberSession(target, TypingRoad.KEYBOARD)
        return TvReply.Ok(if (exact) RemoteStatus.OK else RemoteStatus.PARTIAL)
    }

    /**
     * Forwards the request to TV Dash. Its text is untouched, unless the session began on Bluetooth: then only the
     * part after what Bluetooth already typed is sent, in the same request format. Returns null if TV Dash is
     * unreachable, refuses, or cannot type right now, so the caller can try the Bluetooth keyboard.
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
        when (val reply = link.get(request)) {
            TvReply.Unreachable -> {
                setDash(false)
                dashNudge.trySend(Unit)
                return null
            }
            is TvReply.Rejected -> {
                noteDashRejected(reply.http)
                dashTypingPausedUntil = SystemClock.elapsedRealtime() + DASH_TYPING_PAUSE_MS
                return null
            }
            is TvReply.Ok -> {
                if (reply.body.trim() in BLOCKED_TYPING) {
                    // TV Dash is open but cannot type (no keyboard chosen, no text box, no accessibility): step aside.
                    dashTypingPausedUntil = SystemClock.elapsedRealtime() + DASH_TYPING_PAUSE_MS
                    return null
                }
            }
        }
        setDash(true)
        if (payload.enter) resetSession() else rememberSession(payload.text, TypingRoad.DASH)
        return TvReply.Ok(RemoteStatus.OK)
    }

    /** The keyboard closed. TV Dash has an open composition to finish; the Bluetooth keyboard has nothing to close. */
    private suspend fun finishSession(path: String): TvReply {
        val road = typingRoad
        resetSession()
        heldKeyboard = null
        if (road != TypingRoad.KEYBOARD && dashUp) postToDash(path) // its answer does not matter: there is nothing left to type
        return TvReply.Ok(RemoteStatus.OK)
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
                    withTimeoutOrNull(NOT_SET_UP_RECHECK_MS) { keyboardNudge.receive() }
                    continue
                }
                if (keyboardLink.connect()) {
                    wait = BACKOFF_MIN_MS
                    if (keyboardHintShown) {
                        _hint.value = ""
                        keyboardHintShown = false
                    }
                    setKeys(true)
                    // Suspends while the link stays up; the HID service listener flips this state when it drops.
                    keyboardLink.getConnectionState().first { it != ConnectionState.CONNECTED }
                    setKeys(false)
                } else {
                    setKeys(false)
                    // Say why, so "nothing typed" can be traced to pairing, permission, or the TV being off.
                    keyboardLink.lastProblem?.let {
                        _hint.value = it
                        keyboardHintShown = true
                    }
                    withTimeoutOrNull(wait) { keyboardNudge.receive() }
                    wait = (wait * 2).coerceAtMost(BACKOFF_MAX_MS)
                }
            }
        }
    }
}
