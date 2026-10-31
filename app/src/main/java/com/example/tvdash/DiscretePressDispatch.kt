package com.example.tvdash

/**
 * The deliberately asymmetric fallback policy for Wi-Fi OK:
 * a short press may fall back to D-pad center; a long press must never become a short select.
 */
internal enum class DiscretePressResult(val wireValue: String) {
    SUCCESS("ok"),
    NO_FOCUS("no-focus"),
    LONG_CLICK_UNSUPPORTED("no-long-click"),
    ACTION_FAILED("action-failed")
}

internal object DiscretePressDispatch {
    fun shortOk(
        focusedClick: () -> Boolean,
        fallbackDpadCenter: () -> Boolean
    ): DiscretePressResult {
        if (focusedClick()) return DiscretePressResult.SUCCESS
        return if (fallbackDpadCenter()) {
            DiscretePressResult.SUCCESS
        } else {
            DiscretePressResult.ACTION_FAILED
        }
    }

    /** Pass through a genuine long-click outcome. There is intentionally no short-select fallback. */
    fun longOk(focusedLongClick: () -> DiscretePressResult): DiscretePressResult =
        focusedLongClick()
}
