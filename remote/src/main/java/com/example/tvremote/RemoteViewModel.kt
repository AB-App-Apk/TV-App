package com.example.tvremote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tvlink.HeldRemoteKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * One-time things the screen should do. They are events, not state: each is delivered once to whoever is collecting
 * and is not replayed after a screen rotation.
 */
sealed class UIEvent {
    /** A button press was carried out. */
    object TriggerHapticFeedback : UIEvent()

    /** The connection dropped to Disconnected after having been up. */
    object ShowDisconnectAlert : UIEvent()

    /** A short message for the user. */
    data class ShowToast(val message: String) : UIEvent()

    /** TV Dash answers, so the screen should show its list of the TV's apps. (With only Bluetooth up, nothing opens on the phone.) */
    object OpenAppsList : UIEvent()
}

/** How the phone's own wake-up of a TV that no link reaches turned out. */
sealed interface PhoneWakeOutcome {
    /** The TV answered after all (the link state was a moment behind), so only its screen was woken. */
    object TvAnswered : PhoneWakeOutcome

    /** The phone's infrared blaster sent the power code. */
    object InfraredSent : PhoneWakeOutcome

    /** Wake-on-LAN is running. [why] says what happened to infrared first, for example that the phone has no blaster. */
    data class WakeOnLanStarted(val why: String) : PhoneWakeOutcome

    /** Wake-on-LAN cannot start because no MAC address is saved. */
    data class NeedsMacAddress(val why: String) : PhoneWakeOutcome
}

/**
 * The phone's own way to wake a TV that no link reaches: infrared first, then Wake-on-LAN. It is separate from the
 * connection layer because nothing is connected when it is needed. Layer 2 only asks for it and reports the outcome.
 */
interface PhoneWake {
    /** [onWolFinished] is called with true if the TV woke up, or false after 45 seconds of no answer. */
    suspend fun wake(onWolFinished: (Boolean) -> Unit): PhoneWakeOutcome

    /** Stops a Wake-on-LAN run that is still going. */
    fun cancel()
}

/** [PhoneWake] built on the phone's infrared blaster and Wake-on-LAN. [macAddress] is read when the wake-up starts. */
class GrennoPhoneWake(
    private val tvPower: GrennoTvRemoteManager,
    private val link: TvLink,
    private val macAddress: () -> String
) : PhoneWake {
    companion object {
        const val NO_IR = "No IR blaster on this phone."
        const val NO_38_KHZ = "This phone's IR blaster can't send 38 kHz."
        const val IR_FAILED = "The IR signal could not be sent."
    }

    override suspend fun wake(onWolFinished: (Boolean) -> Unit): PhoneWakeOutcome {
        // The link state can lag a moment behind. If TV Dash answers now, just wake the screen. The IR code is a
        // toggle, so it must never be sent to a TV that is awake: it would switch it off.
        if (link.get("ping") !is TvReply.Unreachable) {
            link.get("power?s=on")
            return PhoneWakeOutcome.TvAnswered
        }
        var why = NO_IR
        if (tvPower.hasIrEmitter()) {
            if (!tvPower.verifyHardwareCompatibility()) {
                why = NO_38_KHZ
            } else if (tvPower.transmitPowerToggle()) {
                return PhoneWakeOutcome.InfraredSent
            } else {
                why = IR_FAILED
            }
        }
        val started = tvPower.executeWakeOnLanFlood(
            macAddress(),
            stopWhen = { link.get("ping") !is TvReply.Unreachable },
            onFinished = onWolFinished
        )
        return if (started) PhoneWakeOutcome.WakeOnLanStarted(why) else PhoneWakeOutcome.NeedsMacAddress(why)
    }

    override fun cancel() {
        tvPower.cancelWakeOnLan()
    }
}

/**
 * Layer 2: the lifecycle-aware ViewModel.
 *
 * It turns taps and typing into requests to Layer 1 and turns what comes back into state and one-time events. It
 * never chooses a transport: every button goes to [ConnectionManager], which decides between TV Dash and the Bluetooth
 * keyboard and falls back silently. The one thing the ViewModel decides is what to tell the user, and in BluetoothOnly
 * it tells the user nothing about power.
 *
 * Ownership: the ViewModel starts the [ConnectionManager] when it is created and stops it in [onCleared], so a screen
 * rotation does not restart the links. The screen must not start or stop the manager itself.
 *
 * Everything runs in [viewModelScope], so when the ViewModel is cleared every observation and every queued request is
 * cancelled with it.
 */
class RemoteViewModel(
    private val connection: ConnectionManager,
    private val phoneWake: PhoneWake
) : ViewModel() {

    /** How well the remote reaches the TV right now. Read-only; Layer 1 is the only writer. */
    val connectionState: StateFlow<RemoteConnectionState> = connection.state

    /**
     * One FIFO for held-button lifecycle events and the next short OK command.
     * The media-key release must finish before a following OK is sent to the TV.
     */
    private val heldInputQueue = CommandQueue(viewModelScope)

    /** A plain sentence about why something is not working, for a status line. Empty when there is nothing to say. Safe to ignore. */
    val hint: StateFlow<String> = connection.hint

    private val _isTvPoweredOff = MutableStateFlow(true)

    /**
     * True while the TV is presumed to be off, so having no connection is expected and is not a fault.
     *  - It starts true: nothing has answered yet, and the person may have opened the remote to switch the TV on.
     *  - It becomes true when this remote switches the TV off, before the connection drops.
     *  - It becomes false when the TV answers again: any link returning after a total loss, or both links up at once.
     * The screen uses it to keep the "looking for the TV" overlay from covering the Power On button.
     */
    val isTvPoweredOff: StateFlow<Boolean> = _isTvPoweredOff.asStateFlow()

    private val _events = MutableSharedFlow<UIEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** One-time events for the screen. Never blocks the sender: if nobody is collecting, the event is dropped. */
    val events: SharedFlow<UIEvent> = _events.asSharedFlow()

    /**
     * Typing requests wait here in the order they were made and are sent one at a time. Nothing is dropped or merged,
     * so the text stream reaches Layer 1 exactly as the keyboard produced it, and Layer 1's own 5-second buffer for
     * a missing link keeps working untouched.
     */
    private val keyboardQueue = Channel<String>(Channel.UNLIMITED)

    init {
        connection.start()

        // Tell the screen when the connection drops. The state at the start is Disconnected, so there is no alert
        // until a connection has actually been up and then lost. A drop caused by switching the TV off is expected,
        // so it gets no alert either.
        viewModelScope.launch {
            var previous: RemoteConnectionState = connection.state.value
            connection.state.collect { now ->
                if (now === RemoteConnectionState.Disconnected && previous !== RemoteConnectionState.Disconnected &&
                    !_isTvPoweredOff.value
                ) {
                    _events.tryEmit(UIEvent.ShowDisconnectAlert)
                }
                // The TV has answered, so it is on: both links are up, or a link came back after a total loss.
                if (now === RemoteConnectionState.FullyConnected ||
                    (previous === RemoteConnectionState.Disconnected && now !== RemoteConnectionState.Disconnected)
                ) {
                    _isTvPoweredOff.value = false
                }
                previous = now
            }
        }

        viewModelScope.launch {
            for (path in keyboardQueue) {
                connection.sendKeyboardPayload(path)
            }
        }
    }

    // ---------------------------------------------------------------- power

    /**
     * Power On, gated by the connection state:
     *  - BluetoothOnly: silent. Layer 1 sends the Wake Up key over the Bluetooth keyboard. No network step, and no
     *    message is ever shown. (It is Wake Up, not the power toggle, so it can never switch a TV that is on off.)
     *  - WiFiOnly or FullyConnected: Layer 1 wakes the screen through TV Dash. The Wake-on-LAN message appears only
     *    if the phone really has to start its own wake-up, because then the message is true.
     *  - Disconnected: nothing answers, so Layer 1 says the phone must wake the TV itself: infrared, then Wake-on-LAN.
     */
    fun onPowerClicked() {
        viewModelScope.launch {
            when (connection.state.value) {
                RemoteConnectionState.BluetoothOnly -> powerSilently()
                RemoteConnectionState.WiFiOnly,
                RemoteConnectionState.FullyConnected,
                RemoteConnectionState.Disconnected -> powerWithNetwork()
            }
        }
    }

    /**
     * Power Off. The connection is about to drop because the TV is switching off, so [isTvPoweredOff] is set first and
     * the screen never treats that drop as a fault. If the TV did not switch off (the command failed and it still
     * answers), the flag goes back.
     */
    fun onPowerOffClicked() {
        viewModelScope.launch {
            _isTvPoweredOff.value = true
            if (send(RemoteCommand.POWER_OFF) == RemoteStatus.OK) {
                haptic()
            } else if (connection.state.value !== RemoteConnectionState.Disconnected) {
                _isTvPoweredOff.value = false
            }
        }
    }

    private suspend fun powerSilently() {
        if (send(RemoteCommand.POWER_ON) == RemoteStatus.OK) haptic()
    }

    private suspend fun powerWithNetwork() {
        when (send(RemoteCommand.POWER_ON)) {
            RemoteStatus.OK -> haptic()
            RemoteStatus.WAKE_NEEDED -> wakeFromPhone()
            else -> Unit
        }
    }

    /** No link reaches the TV: the phone wakes it itself, and the messages below are true because that is what happens. */
    private suspend fun wakeFromPhone() {
        val outcome = phoneWake.wake { awake ->
            toast(if (awake) "The TV is awake." else "No answer after 45 seconds. This TV may not wake from the network.")
        }
        when (outcome) {
            PhoneWakeOutcome.TvAnswered -> haptic()
            PhoneWakeOutcome.InfraredSent -> {
                haptic()
                toast("IR power signal sent. Point the phone's IR window at the TV.")
            }
            is PhoneWakeOutcome.WakeOnLanStarted -> toast("${outcome.why} Wake-on-LAN started...")
            is PhoneWakeOutcome.NeedsMacAddress -> toast("${outcome.why} Add the TV's MAC address under Change TV to use Wake-on-LAN.")
        }
    }

    // ---------------------------------------------------------------- apps and system buttons

    /**
     * Apps goes to Layer 1, which decides from the state. TV Dash answering: the screen shows the phone's list of the
     * TV's apps (the [UIEvent.OpenAppsList] event). Only Bluetooth up (BluetoothOnly): Layer 1 skips TV Dash and sends
     * the All Apps key (Android's KEYCODE_ALL_APPS, 284) over the Bluetooth keyboard, and nothing opens on the phone.
     */
    fun onAppsClicked() {
        viewModelScope.launch {
            when ((connection.openApps() as? DeliveryResult.Delivered)?.reply) {
                RemoteStatus.LIST -> {
                    haptic()
                    _events.tryEmit(UIEvent.OpenAppsList)
                }
                RemoteStatus.OK -> haptic()
                else -> Unit
            }
        }
    }

    fun onHomeClicked() {
        press(RemoteCommand.HOME)
    }

    fun onBackClicked() {
        press(RemoteCommand.BACK)
    }

    fun onSettingsClicked() {
        press(RemoteCommand.SETTINGS)
    }

    // ---------------------------------------------------------------- d-pad

    fun onDpadUp() {
        press(RemoteCommand.UP)
    }

    fun onDpadDown() {
        press(RemoteCommand.DOWN)
    }

    fun onDpadLeft() {
        press(RemoteCommand.LEFT)
    }

    fun onDpadRight() {
        press(RemoteCommand.RIGHT)
    }

    fun onDpadCenter() {
        press(RemoteCommand.OK)
    }

    // ---------------------------------------------------------------- volume and media

    fun onVolumeUp() {
        press(RemoteCommand.VOLUME_UP)
    }

    fun onVolumeDown() {
        press(RemoteCommand.VOLUME_DOWN)
    }

    fun onMuteClicked() {
        press(RemoteCommand.MUTE)
    }

    fun onRewindClicked() {
        press(RemoteCommand.REWIND)
    }

    fun onForwardClicked() {
        press(RemoteCommand.FORWARD)
    }

    // ---------------------------------------------------------------- virtual keyboard

    /**
     * The phone's text box changed. [text] is everything typed so far, as the keyboard has always sent it. Becomes
     * "text?t=<text>&enter=0", or "text?t=<text>&enter=1" when [isSubmit] is true. The request goes to Layer 1 in order
     * and untouched; Layer 1 types it over Bluetooth or hands it to TV Dash, and holds it for 5 seconds if no link is up.
     */
    fun onKeyboardInputChanged(text: String, isSubmit: Boolean) {
        keyboardQueue.trySend("text?t=${URLEncoder.encode(text, "UTF-8")}&enter=${if (isSubmit) 1 else 0}")
    }

    /** The keyboard panel was closed: "text?done=1". */
    fun onKeyboardClosed() {
        keyboardQueue.trySend("text?done=1")
    }

    // ---------------------------------------------------------------- held-button gestures

    /** Begins one physical touch hold in the ordered input queue. */
    fun onHeldInputDown(key: HeldRemoteKey, holdId: String) {
        heldInputQueue.submit {
            connection.beginHeldInput(holdId, key)
        }
    }

    /** Ends the same hold in queue order, before a later short OK is sent. */
    fun onHeldInputUp(holdId: String, cancelled: Boolean = false, longPress: Boolean = false) {
        heldInputQueue.submit {
            connection.endHeldInput(holdId, cancelled, longPress)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Sends one button to Layer 1 and gives a haptic tap if it was carried out. Silent otherwise. */
    private fun press(command: RemoteCommand) {
        if (command == RemoteCommand.OK) {
            // The center OK is a one-shot button, not a held-input gesture. Still queue it after FF/REW UP
            // so it cannot race the media-key release coroutine.
            heldInputQueue.submit {
                if (send(command) == RemoteStatus.OK) haptic()
            }
        } else {
            viewModelScope.launch {
                if (send(command) == RemoteStatus.OK) haptic()
            }
        }
    }

    /** The plain status word Layer 1 answered with, or null. */
    private suspend fun send(command: RemoteCommand): String? =
        (connection.sendRemoteCommand(command) as? DeliveryResult.Delivered)?.reply

    private fun haptic() {
        _events.tryEmit(UIEvent.TriggerHapticFeedback)
    }

    private fun toast(message: String) {
        _events.tryEmit(UIEvent.ShowToast(message))
    }

    override fun onCleared() {
        keyboardQueue.close()
        heldInputQueue.close()
        phoneWake.cancel()
        connection.stop()
    }

    /** Creates the ViewModel with the pieces it needs. The screen builds the [ConnectionManager] and [PhoneWake] and hands them in. */
    class Factory(
        private val connection: ConnectionManager,
        private val phoneWake: PhoneWake
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RemoteViewModel::class.java)) { "Unknown ViewModel class ${modelClass.name}" }
            return RemoteViewModel(connection, phoneWake) as T
        }
    }
}
