package com.example.tvremote

import android.content.SharedPreferences
import com.example.tvlink.TvTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The result of checking what was typed on the setup form, before the TV is asked anything. */
sealed interface InputCheck {
    /** Both fields are filled in and look right. */
    data class Valid(val host: String, val pin: String) : InputCheck

    /** Something is missing or malformed. [message] is ready to show; the flags say which field to mark. */
    data class Invalid(val message: String, val ipWrong: Boolean, val pinWrong: Boolean) : InputCheck
}

/** What the TV said, over Wi-Fi only, when asked whether this address and PIN are right. */
enum class TvCheck { ACCEPTED, WRONG_PIN, UNREACHABLE, CERTIFICATE }

/**
 * Checks the IP address and PIN from the setup form.
 *
 * Three steps, and the remote is never opened by any one of them alone:
 *  1. [check]: both fields are filled in, the address is a real IPv4 address, the PIN is numbers.
 *  2. [readFingerprint]: the TV shows its certificate. The person compares the security code on both screens. Nothing
 *     secret is sent yet, so a device pretending to be the TV never receives the PIN.
 *  3. [askTv]: the TV itself answers a request made with that address, PIN and verified certificate. TV Dash checks
 *     the PIN on every request, so an answer proves the address, the PIN and the certificate.
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
            !Regex("[0-9]{4}").matches(pin) ->
                InputCheck.Invalid(
                    "The PIN must be the four digits shown on the TV.",
                    ipWrong = false,
                    pinWrong = true
                )
            else -> InputCheck.Valid(host, pin)
        }
    }

    /**
     * Reads the SHA-256 fingerprint of the certificate the TV at [host] presents, or null if it does not answer
     * with TLS (it is off, on another network, or TV Dash is an older build without encryption). Sends nothing.
     */
    suspend fun readFingerprint(host: String): String? =
        withContext(Dispatchers.IO) { TvTls.fetchFingerprint(host, TV_PORT) }

    /**
     * Asks TV Dash on [host] whether [pin] is right, trusting only the certificate with [fingerprint]. This uses its own
     * Wi-Fi-only link built from exactly what was typed, so nothing is saved and the Bluetooth road cannot make a wrong
     * address look right. One more try after a short pause, so a single lost packet does not reject a good address.
     */
    suspend fun askTv(host: String, pin: String, fingerprint: String): TvCheck {
        val link = TvLink({ host }, { pin }, null, { fingerprint })
        for (attempt in 1..2) {
            when (val reply = link.get("ping")) {
                is TvReply.Ok -> return TvCheck.ACCEPTED
                is TvReply.Rejected -> return if (reply.http == 403) TvCheck.WRONG_PIN else TvCheck.UNREACHABLE
                TvReply.Unreachable -> {
                    if (link.certificateMismatch) return TvCheck.CERTIFICATE
                    if (attempt == 1) delay(500)
                }
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
        const val FINGERPRINT = "tls_fp"
    }

    val isComplete: Boolean
        get() {
            if (prefs.contains(DONE)) return prefs.getBoolean(DONE, false)
            // A phone set up before this gate existed counts if it has both an address and a PIN saved.
            return !prefs.getString("host", "").isNullOrBlank() && !prefs.getString("pin", "").isNullOrBlank()
        }

    /** The way in that was completed: "ip", "bluetooth", or null before any. */
    val method: String? get() = prefs.getString(METHOD, null)

    /**
     * The SHA-256 fingerprint of the TV's certificate, saved after the person compared the security code. Empty on a
     * phone set up before Wi-Fi was encrypted: it must verify the TV once before Wi-Fi works again.
     */
    val fingerprint: String get() = prefs.getString(FINGERPRINT, "")?.trim().orEmpty()

    fun pinFingerprint(fingerprint: String) {
        prefs.edit().putString(FINGERPRINT, TvTls.normalize(fingerprint)).apply()
    }

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
