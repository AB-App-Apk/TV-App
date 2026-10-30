package com.example.tvdash

/**
 * The deliberately asymmetric fallback policy for Wi-Fi OK:
 * a short press may fall back to D-pad center; a long press must never become a short select.
 */
internal enum class DiscretePressResult(val wireValue: String) {
    SUCCESS("ok"),
    ACTION_FAILED("action-failed"),
    LONG_CLICK_UNSUPPORTED("no-long-click")
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

    fun longOk(focusedLongClick: () -> Boolean): DiscretePressResult =
        if (focusedLongClick()) {
            DiscretePressResult.SUCCESS
        } else {
            DiscretePressResult.LONG_CLICK_UNSUPPORTED
        }
}
