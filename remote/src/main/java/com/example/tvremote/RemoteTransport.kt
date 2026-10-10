package com.example.tvremote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Every button the remote can send, independent of how it travels. [wifiPath] is the request TV Dash understands. */
enum class RemoteCommand(val wifiPath: String) {
    UP("key?k=up"), DOWN("key?k=down"), LEFT("key?k=left"), RIGHT("key?k=right"), OK("key?k=ok"),
    BACK("key?k=back"), HOME("key?k=home"), SETTINGS("key?k=settings"),
    VOLUME_UP("vol?d=up"), VOLUME_DOWN("vol?d=down"), MUTE("vol?d=mute"),
    REWIND("key?k=rew"), FORWARD("key?k=ff"), POWER_OFF("power?s=off"),
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** What a send attempt came to. Transports return Delivered or Unsupported; only the manager returns Offline. */
sealed interface DeliveryResult {
    /** Delivered. [reply] is TV Dash's answer over Wi-Fi ("ok", "no-a11y", ...) and "ok" over Bluetooth. */
    data class Delivered(val reply: String) : DeliveryResult

    /** This transport has no way to send that button (for example Settings over Bluetooth). */
    data object Unsupported : DeliveryResult

    /** Neither transport could reach the TV. */
    data object Offline : DeliveryResult
}

/** One way of getting a button press to the TV. The screen never needs to know which one is in use. */
interface RemoteTransport {
    fun getConnectionState(): StateFlow<ConnectionState>

    /** Opens the link. Returns true when the TV is reachable over this transport. */
    suspend fun connect(): Boolean

    suspend fun disconnect()

    /** Delivers one command. Throws [IOException] when the TV cannot be reached over this transport. */
    @Throws(IOException::class)
    suspend fun sendCommand(command: RemoteCommand): DeliveryResult
}

/** The Wi-Fi transport: the PIN-protected HTTP requests TV Dash answers on port 8765. */
class WifiTransport(private val link: TvLink) : RemoteTransport {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)

    override fun getConnectionState(): StateFlow<ConnectionState> = state.asStateFlow()

    /** The handshake: TV Dash answers a ping only when the TV is awake and the PIN matches or is refused. */
    override suspend fun connect(): Boolean {
        state.value = ConnectionState.CONNECTING
        val answered = link.get("ping") !is TvReply.Unreachable
        state.value = if (answered) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
        return answered
    }

    /** HTTP keeps no connection open, so there is nothing to close. */
    override suspend fun disconnect() {
        state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun sendCommand(command: RemoteCommand): DeliveryResult =
        when (val reply = link.get(command.wifiPath)) {
            is TvReply.Ok -> {
                state.value = ConnectionState.CONNECTED
                DeliveryResult.Delivered(reply.body.trim())
            }
            // A refusal still means the TV answered, so Wi-Fi works; the screen explains the PIN problem.
            is TvReply.Rejected -> {
                state.value = ConnectionState.CONNECTED
                DeliveryResult.Delivered("rejected-${reply.http}")
            }
            TvReply.Unreachable -> {
                state.value = ConnectionState.DISCONNECTED
                throw IOException("The TV did not answer over Wi-Fi")
            }
        }
}
