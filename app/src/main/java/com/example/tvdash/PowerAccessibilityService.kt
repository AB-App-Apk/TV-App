package com.example.tvdash

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The remote's hands: D-pad, Back, sleep and typing into text boxes.
 *
 * Built to stay alive on a small TV. It keeps no node references or caches, so it stays tiny. It reacts only to
 * window changes, and it catches every failure, because a crash is what makes Android mark an accessibility
 * service as "not working" and stop rebinding it.
 */
class PowerAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "TvDashA11y"
        private const val WATCHDOG_MS = 15_000L
        private const val EVENT_GAP_MS = 5_000L
        private const val LONG_PRESS_GESTURE_MS = 800L
        @Volatile var instance: PowerAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastWindowCheck = 0L

    /** While connected, keeps confirming that the phone listener is alive and restarts it if Android removed it. */
    private val watchdog = object : Runnable {
        override fun run() {
            safely("watchdog") { RemoteService.ensureRunning(this@PowerAccessibilityService) }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onServiceConnected() {
        A11yDiagnostics.recordEvent(this, "a11y-connected")
        Log.i(TAG, "lifecycle=onServiceConnected pid=" + android.os.Process.myPid() + " elapsedMs=" + SystemClock.elapsedRealtime())
        super.onServiceConnected()
        safely("onServiceConnected") { attach() }
    }

    override fun onRebind(intent: Intent?) {
        A11yDiagnostics.recordEvent(this, "a11y-rebind")
        Log.i(TAG, "lifecycle=onRebind pid=" + android.os.Process.myPid() + " elapsedMs=" + SystemClock.elapsedRealtime())
        super.onRebind(intent)
        safely("onRebind") { attach() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        A11yDiagnostics.recordEvent(this, "a11y-unbind", "system unbound service")
        Log.w(TAG, "lifecycle=onUnbind pid=" + android.os.Process.myPid() + " elapsedMs=" + SystemClock.elapsedRealtime())
        safely("onUnbind") { detach("unbound by the system") }
        super.onUnbind(intent)
        return true // ask for onRebind() if Android binds this same instance again
    }

    override fun onDestroy() {
        A11yDiagnostics.recordEvent(this, "a11y-destroyed")
        Log.w(TAG, "lifecycle=onDestroy pid=" + android.os.Process.myPid() + " elapsedMs=" + SystemClock.elapsedRealtime())
        safely("onDestroy") { detach("destroyed") }
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.i(TAG, "interrupt ignored")
    }

    /**
     * Only window changes matter: when the user opens a heavy app (YouTube, a browser), confirm the listener is
     * alive. At most once per few seconds, and never on the event thread.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowCheck < EVENT_GAP_MS) return
        lastWindowCheck = now
        handler.post { safely("window change") { RemoteService.ensureRunning(this) } }
    }

    private fun attach() {
        instance = this
        AccessGuard.onConnected()
        A11yDiagnostics.recordEvent(this, "a11y-attach", "requesting remote listener")
        Log.i(TAG, "requesting RemoteService after accessibility connect pid=" + android.os.Process.myPid() + " elapsedMs=" + SystemClock.elapsedRealtime())
        RemoteService.ensureRunning(this)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        Log.i(TAG, "connected")
    }

    private fun detach(why: String) {
        Log.w(TAG, "disconnected: $why")
        handler.removeCallbacks(watchdog)
        if (instance === this) instance = null
        AccessGuard.onDisconnected()
    }

    fun sleep() {
        safely("sleep") { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
    }

    /** Performs a normal click on the currently selected TV control. */
    fun performFocusedClick(): Boolean {
        return try {
            val focused = findFocusedNode() ?: return false
            val performed = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "focused click package=${focused.packageName} class=${focused.className} performed=$performed")
            performed
        } catch (t: Throwable) {
            Log.w(TAG, "focused click failed", t)
            false
        }
    }

    /**
     * Legacy Boolean API retained for the older held-key watchdog.
     * The discrete Wi-Fi route uses [performFocusedLongClickResult] so it can report the
     * difference between missing focus, unsupported long-click, and execution failure.
     */
    fun performFocusedLongClick(): Boolean =
        performFocusedLongClickResult() == DiscretePressResult.SUCCESS

    /**
     * Prefer the node's native long-click action. If the app does not expose it (or rejects it),
     * emulate a physical touch-and-hold at the focused control's screen center.
     */
    internal fun performFocusedLongClickResult(): DiscretePressResult {
        return try {
            val focused = findFocusedNode()
                ?: run {
                    Log.w(TAG, "long-ok target lookup found no focused accessibility node")
                    return DiscretePressResult.NO_FOCUS
                }
            logLongOkTarget(focused)

            val longClickId = AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id
            val supported = focused.isLongClickable ||
                focused.actionList.any { it.id == longClickId }

            if (supported) {
                val performed = focused.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                Log.i(TAG, "focused long-click package=${focused.packageName} class=${focused.className} performed=$performed")
                if (performed) return DiscretePressResult.SUCCESS
                Log.i(TAG, "native long-click was rejected; trying touch-hold fallback")
            } else {
                Log.i(TAG, "focused node does not expose long-click; trying touch-hold fallback package=${focused.packageName} class=${focused.className}")
            }

            dispatchFocusedTouchHold(focused)
        } catch (t: Throwable) {
            Log.w(TAG, "focused long-click failed", t)
            DiscretePressResult.ACTION_FAILED
        }
    }

    /** Records the exact kind of focused node used by native long-click or touch-hold. */
    private fun logLongOkTarget(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        Log.i(
            TAG,
            "long-ok target package=${node.packageName} class=${node.className} " +
                "focused=${node.isFocused} accessibilityFocused=${node.isAccessibilityFocused} " +
                "visible=${node.isVisibleToUser} clickable=${node.isClickable} " +
                "longClickable=${node.isLongClickable} bounds=$bounds"
        )
    }

    /**
     * Dispatches a stationary 800 ms touch at the center of the focused node.
     * A successful return means Android accepted the gesture; the callback records
     * whether the gesture subsequently completed or was cancelled.
     */
    private fun dispatchFocusedTouchHold(focused: AccessibilityNodeInfo): DiscretePressResult {
        if (!focused.isVisibleToUser) {
            Log.w(TAG, "touch-hold target is not visible package=${focused.packageName} class=${focused.className}")
            return DiscretePressResult.ACTION_FAILED
        }

        val bounds = Rect()
        focused.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            Log.w(TAG, "touch-hold target has empty screen bounds package=${focused.packageName} class=${focused.className}")
            return DiscretePressResult.ACTION_FAILED
        }

        val packageName = focused.packageName?.toString() ?: "unknown"
        val className = focused.className?.toString() ?: "unknown"
        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_GESTURE_MS))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "touch-hold completed package=$packageName class=$className bounds=$bounds durationMs=$LONG_PRESS_GESTURE_MS")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "touch-hold cancelled package=$packageName class=$className bounds=$bounds")
                }
            },
            handler
        )

        Log.i(
            TAG,
            "touch-hold dispatch accepted=$accepted package=$packageName class=$className bounds=$bounds durationMs=$LONG_PRESS_GESTURE_MS"
        )
        return if (accepted) DiscretePressResult.SUCCESS else DiscretePressResult.ACTION_FAILED
    }

    /**
     * Resolves the currently selected control consistently across interactive windows.
     * D-pad input focus is preferred to accessibility focus because the latter can point at a
     * different item from the one highlighted by the TV's remote-navigation system.
     */
    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()

        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "active accessibility root unavailable", t)
        }
        try {
            windows.forEach { window ->
                window.root?.let { roots.add(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "interactive accessibility windows unavailable", t)
        }

        // Search every available window for input focus before considering accessibility focus.
        for (root in roots) {
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (t: Throwable) {
                Log.w(TAG, "input-focus lookup failed", t)
                null
            }
            if (focused != null) return focused
        }

        for (root in roots) {
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            } catch (t: Throwable) {
                Log.w(TAG, "accessibility-focus lookup failed", t)
                null
            }
            if (focused != null) return focused
        }

        for (root in roots) {
            val focused = findViewFocusedNode(root, 0)
            if (focused != null) return focused
        }
        return null
    }

    private fun findViewFocusedNode(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isFocused) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val focused = findViewFocusedNode(child, depth + 1)
            if (focused != null) return focused
        }
        return null
    }

    /** Types into whichever text field has input focus (e.g. a search bar). Never throws. */
    fun typeText(text: String, enter: Boolean): Boolean = try {
        val node = windows.mapNotNull { it.root }.firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            false
        } else {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && enter && Build.VERSION.SDK_INT >= 30)
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ok
        }
    } catch (t: Throwable) {
        Log.w(TAG, "typeText failed", t)
        false
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
        }
    }
}
