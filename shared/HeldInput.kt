package com.example.tvlink

/**
 * Buttons which can be held by the remote. The wire names are part of the phone/TV protocol.
 */
enum class HeldRemoteKey(val wireName: String) {
    BACK("back"),
    OK("ok"),
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
    UP("up");

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
