package com.example.tvremote

import android.content.SharedPreferences
import kotlinx.coroutines.delay

/** The result of checking what was typed on the setup form, before the TV is asked anything. */
sealed interface InputCheck {
    /** Both fields are filled in and look right. */
    data class Valid(val host: String, val pin: String) : InputCheck

    /** Something is missing or malformed. [message] is ready to show; the flags say which field to mark. */
    data class Invalid(val message: String, val ipWrong: Boolean, val pinWrong: Boolean) : InputCheck
}

/** What the TV said, over Wi-Fi only, when asked whether this address and PIN are right. */
enum class TvCheck { ACCEPTED, WRONG_PIN, UNREACHABLE }

/**
 * Checks the IP address and PIN from the setup form.
 *
 * Two steps, and the remote is never opened by either one alone:
 *  1. [check]: both fields are filled in, the address is a real IPv4 address, the PIN is numbers.
 *  2. [askTv]: the TV itself answers a request made with that address and PIN. TV Dash checks the PIN on every
 *     request, so an answer proves both the address and the PIN.
 */
object ConnectionInput {

    fun check(hostText: String, pinText: String): InputCheck {
        val host = hostText.trim()
        val pin = pinText.trim()
        return when {
            host.isEmpty() && pin.isEmpty() ->
                InputCheck.Invalid("Enter the TV's IP address and the PIN first.", ipWrong = true, pinWrong = true)
            host.isEmpty() ->
                InputCheck.Invalid("Enter the TV's IP address.", ipWrong = true, pinWrong = false)
            pin.isEmpty() ->
                InputCheck.Invalid("Enter the PIN shown on the TV.", ipWrong = false, pinWrong = true)
            !isIpv4(host) ->
                InputCheck.Invalid("The IP address should look like 192.168.1.20.", ipWrong = true, pinWrong = false)
            !pin.all { it.isDigit() } ->
                InputCheck.Invalid("The PIN is numbers only.", ipWrong = false, pinWrong = true)
            else -> InputCheck.Valid(host, pin)
        }
    }

    /**
     * Asks TV Dash on [host] whether [pin] is right. This uses its own Wi-Fi-only link built from exactly what was
     * typed, so nothing is saved and the Bluetooth road cannot make a wrong address look right. One more try after a
     * short pause, so a single lost packet does not reject a good address.
     */
    suspend fun askTv(host: String, pin: String): TvCheck {
        val link = TvLink({ host }, { pin })
        for (attempt in 1..2) {
            when (val reply = link.get("ping")) {
                is TvReply.Ok -> return TvCheck.ACCEPTED
                is TvReply.Rejected -> return if (reply.http == 403) TvCheck.WRONG_PIN else TvCheck.UNREACHABLE
                TvReply.Unreachable -> if (attempt == 1) delay(500)
            }
        }
        return TvCheck.UNREACHABLE
    }

    private fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() in 0..255
        }
    }
}

/**
 * The one gate in front of the remote. [isComplete] is true only after one of the two ways in has really worked:
 * the TV answered the IP address and PIN ([completeWithIp]), or the Bluetooth keyboard link to the TV came up
 * ([completeWithBluetooth]). Nothing else writes it, and the remote screen is shown only while it is true.
 */
class SetupGate(private val prefs: SharedPreferences) {
    private companion object {
        const val DONE = "setup_done"
        const val METHOD = "setup_method"
    }

    val isComplete: Boolean
        get() {
            if (prefs.contains(DONE)) return prefs.getBoolean(DONE, false)
            // A phone set up before this gate existed counts if it has both an address and a PIN saved.
            return !prefs.getString("host", "").isNullOrBlank() && !prefs.getString("pin", "").isNullOrBlank()
        }

    /** The way in that was completed: "ip", "bluetooth", or null before any. */
    val method: String? get() = prefs.getString(METHOD, null)

    fun completeWithIp(host: String, pin: String, mac: String) {
        prefs.edit()
            .putString("host", host).putString("pin", pin).putString("mac", mac)
            .putBoolean(DONE, true).putString(METHOD, "ip")
            .apply()
    }

    fun completeWithBluetooth() {
        prefs.edit().putBoolean(DONE, true).putString(METHOD, "bluetooth").apply()
    }
}
