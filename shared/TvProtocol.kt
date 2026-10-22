package com.example.tvlink

import java.util.UUID

/** Values TvDash and TvRemote must agree on. This file is compiled into both apps from the shared/ folder. */
object TvProtocol {
    /** Header carrying the PIN; never put authentication material in the request URL. */
    const val PIN_HEADER = "X-TV-PIN"

    private const val MAX_REQUEST_HEADER_LINES = 64

    /** Builds the Wi-Fi URL without accepting authentication data as an argument. */
    fun buildWifiUrl(address: String, port: Int, path: String): String =
        "http://$address:$port/${path.trimStart('/')}"

    /** Builds the Bluetooth HTTP request with the PIN only in its header. */
    fun buildBluetoothRequest(path: String, pin: String): String =
        "GET /" + path.trimStart('/') + " HTTP/1.1\r\n" + PIN_HEADER + ": " + pin + "\r\n\r\n"

    /**
     * Reads a bounded set of HTTP header lines. The supplied reader should return one header at a time,
     * return null at EOF, and return an empty string for the terminating blank line.
     */
    fun readPinHeader(readLine: () -> String?): String? {
        var suppliedPin: String? = null
        var headers = 0
        while (headers++ < MAX_REQUEST_HEADER_LINES) {
            val header = readLine() ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0 && header.substring(0, colon).equals(PIN_HEADER, ignoreCase = true)) {
                suppliedPin = header.substring(colon + 1).trim()
            }
        }
        return suppliedPin
    }

    /** The name TvDash gives its Bluetooth service. */
    const val BT_SERVICE_NAME = "TvDash remote link"

    /** Identifies TvDash's Bluetooth data channel (classic Bluetooth, RFCOMM). Never change it in only one app. */
    val BT_UUID: UUID = UUID.fromString("e93bf259-7cb5-4805-bfb8-0fcb6f7da3ff")
}
