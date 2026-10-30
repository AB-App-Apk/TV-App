package com.example.tvlink

/**
 * Buttons which can be held by the remote. The wire names are part of the phone/TV protocol.
 */
enum class HeldRemoteKey(val wireName: String) {
    BACK("back"),
    OK("ok"),
    UP("up"),
    DOWN("down"),
    FORWARD("ff"),
    REWIND("rew");

    companion object {
        fun fromWire(value: String?): HeldRemoteKey? =
            values().firstOrNull { it.wireName == value }
    }
}

/** A hold is an explicit state transition; it is never implemented by a local repeat timer. */
enum class HeldAction(val wireName: String) {
    DOWN("down"),
    RENEW("renew"),
    UP("up"),
    /** Release a hold without converting a cancelled gesture into a short tap. */
    CANCEL("cancel");

    companion object {
        fun fromWire(value: String?): HeldAction? =
            values().firstOrNull { it.wireName == value }
    }
}

/** Protocol helpers shared by both APKs, since ../shared is compiled into each application. */
object HeldInputProtocol {
    /** Safety lease deadline; sustained holds renew before this expires. */
    const val WATCHDOG_TIMEOUT_MS = 4_000L
    const val LEASE_RENEW_INTERVAL_MS = 1_000L

    /** UUID-shaped identifiers prevent two concurrent button gestures from releasing each other. */
    fun isValidHoldId(value: String): Boolean =
        value.length == 36 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-' }

    fun path(holdId: String, key: HeldRemoteKey, action: HeldAction): String {
        require(isValidHoldId(holdId)) { "Invalid held-input identifier" }
        return "hold?id=$holdId&key=${key.wireName}&action=${action.wireName}"
    }
}


/**
 * One completed Back/OK gesture. It deliberately carries the final intent rather than a streamed key state.
 * The request id also makes retries across Wi-Fi/Bluetooth carrier handover idempotent on the TV.
 */
object RemotePressProtocol {
    const val ACTION_TAP = "tap"
    const val ACTION_LONG = "long"

    fun path(requestId: String, key: HeldRemoteKey, longPress: Boolean): String {
        require(HeldInputProtocol.isValidHoldId(requestId)) { "Invalid press request identifier" }
        require(key == HeldRemoteKey.BACK || key == HeldRemoteKey.OK) { "Only Back and OK have discrete press actions" }
        val action = if (longPress) ACTION_LONG else ACTION_TAP
        return "press?id=$requestId&key=${key.wireName}&action=$action"
    }
}
