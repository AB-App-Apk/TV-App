package com.example.tvdash

import android.accessibilityservice.AccessibilityService
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
        private val YOUTUBE_PACKAGES = setOf("com.google.android.youtube.tv", "com.google.android.youtube")
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

    /** Performs an accessibility click only when the Short-OK D-pad-center action was rejected. */
    fun performFocusedClick(): Boolean {
        return try {
            val focused = findFocusedNode()
            if (focused == null) {
                Log.w(TAG, "short-ok dispatch=ACTION_CLICK target=none")
                return false
            }
            val bounds = Rect()
            focused.getBoundsInScreen(bounds)
            Log.i(
                TAG,
                "short-ok ACTION_CLICK target package=" + focused.packageName +
                    " class=" + focused.className +
                    " inputFocused=" + focused.isFocused +
                    " accessibilityFocused=" + focused.isAccessibilityFocused +
                    " visible=" + focused.isVisibleToUser +
                    " clickable=" + focused.isClickable +
                    " bounds=" + bounds
            )
            val performed = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(
                TAG,
                "short-ok dispatch=ACTION_CLICK performed=" + performed +
                    " package=" + focused.packageName + " class=" + focused.className
            )
            performed
        } catch (t: Throwable) {
            Log.w(TAG, "short-ok dispatch=ACTION_CLICK failed", t)
            false
        }
    }

    /**
     * YouTube Long OK uses the validated native uinput KEY_ENTER hold. A fallback is allowed only
     * when staging/root launch fails before the native process can begin a key transition. Once
     * KEY_DOWN may have been emitted, an uncertain result is terminal to prevent double dispatch.
     */
    internal fun performLongOkResult(): DiscretePressResult {
        val activePackage = activeWindowPackageName()
        if (activePackage in YOUTUBE_PACKAGES) {
            Log.i(TAG, "long-ok route=uinput-key-enter package=$activePackage")
            return when (UinputEnterHold.dispatch(this)) {
                UinputEnterHold.Result.SENT -> DiscretePressResult.SUCCESS
                UinputEnterHold.Result.UNAVAILABLE -> performFocusedAccessibilityLongClickResult()
                UinputEnterHold.Result.UNCERTAIN -> {
                    Log.e(TAG, "long-ok uinput result uncertain; refusing fallback to prevent duplicate action")
                    DiscretePressResult.ACTION_FAILED
                }
            }
        }

        // Preserve ordinary app behavior without using touch.
        Log.i(TAG, "long-ok route=accessibility package=${activePackage ?: "unknown"}")
        return performFocusedAccessibilityLongClickResult()
    }

    /** Returns the foreground accessibility window's package without assuming it is touch-capable. */
    private fun activeWindowPackageName(): String? {
        try {
            rootInActiveWindow?.packageName?.toString()?.let { return it }
        } catch (t: Throwable) {
            Log.w(TAG, "active window package lookup failed", t)
        }
        return try {
            windows.firstOrNull { it.isFocused }?.root?.packageName?.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "focused window package lookup failed", t)
            null
        }
    }

    /** Legacy Boolean API retained for any older caller. */
    fun performFocusedLongClick(): Boolean =
        performLongOkResult() == DiscretePressResult.SUCCESS

    /**
     * Non-touch fallback for devices where the root input command is unavailable. This only
     * invokes ACTION_LONG_CLICK when the focused node advertises that action; it never taps/selects.
     */
    private fun performFocusedAccessibilityLongClickResult(): DiscretePressResult {
        return try {
            val focused = findFocusedNode()
                ?: run {
                    Log.w(TAG, "long-ok accessibility fallback found no focused node")
                    return DiscretePressResult.NO_FOCUS
                }
            logLongOkTarget(focused)

            val longClickId = AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id
            val supported = focused.isLongClickable ||
                focused.actionList.any { it.id == longClickId }

            if (!supported) {
                Log.i(TAG, "long-ok accessibility fallback unsupported package=${focused.packageName} class=${focused.className}")
                return DiscretePressResult.LONG_CLICK_UNSUPPORTED
            }

            val performed = focused.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            Log.i(TAG, "long-ok ACTION_LONG_CLICK package=${focused.packageName} class=${focused.className} performed=$performed")
            if (performed) DiscretePressResult.SUCCESS else DiscretePressResult.ACTION_FAILED
        } catch (t: Throwable) {
            Log.w(TAG, "long-ok accessibility fallback failed", t)
            DiscretePressResult.ACTION_FAILED
        }
    }

    /** Records the focused node considered by the non-touch accessibility fallback. */
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
