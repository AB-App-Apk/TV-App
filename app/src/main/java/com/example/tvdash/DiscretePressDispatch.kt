package com.example.tvdash

/**
 * Wi-Fi Short OK first uses Android's D-pad-center action, matching a physical or keyboard OK press.
 * A focused accessibility click is only a fallback when Android cannot dispatch D-pad center.
 * A long press must never become a short select.
 */
internal enum class DiscretePressResult(val wireValue: String) {
    SUCCESS("ok"),
    NO_FOCUS("no-focus"),
    LONG_CLICK_UNSUPPORTED("no-long-click"),
    ACTION_FAILED("action-failed")
}

internal object DiscretePressDispatch {
    fun shortOk(
        dpadCenter: () -> Boolean,
        focusedClick: () -> Boolean
    ): DiscretePressResult {
        if (dpadCenter()) return DiscretePressResult.SUCCESS
        return if (focusedClick()) {
            DiscretePressResult.SUCCESS
        } else {
            DiscretePressResult.ACTION_FAILED
        }
    }

    /** Pass through a genuine long-click outcome. There is intentionally no short-select fallback. */
    fun longOk(focusedLongClick: () -> DiscretePressResult): DiscretePressResult =
        focusedLongClick()
}
