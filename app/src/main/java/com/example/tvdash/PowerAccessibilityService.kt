package com.example.tvdash

import android.accessibilityservice.AccessibilityService
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
        super.onServiceConnected()
        safely("onServiceConnected") { attach() }
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        safely("onRebind") { attach() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        safely("onUnbind") { detach("unbound by the system") }
        super.onUnbind(intent)
        return true // ask for onRebind() if Android binds this same instance again
    }

    override fun onDestroy() {
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
