package com.example.tvlink

import java.util.UUID

/** Values TvDash and TvRemote must agree on. This file is compiled into both apps from the shared/ folder. */
object TvProtocol {
    /** Header carrying the PIN; never put authentication material in the request URL. */
    const val PIN_HEADER = "X-TV-PIN"

    /** The name TvDash gives its Bluetooth service. */
    const val BT_SERVICE_NAME = "TvDash remote link"

    /** Identifies TvDash's Bluetooth data channel (classic Bluetooth, RFCOMM). Never change it in only one app. */
    val BT_UUID: UUID = UUID.fromString("e93bf259-7cb5-4805-bfb8-0fcb6f7da3ff")
}
