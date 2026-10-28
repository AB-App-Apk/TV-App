package com.example.tvdash

import android.os.SystemClock
import com.example.tvlink.HeldAction
import com.example.tvlink.HeldInputProtocol
import com.example.tvlink.HeldRemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns network-originated held keys. Every DOWN is paired with an UP from the phone or when its renewable
 * four-second safety lease expires. The callbacks are injected so this class does not depend on Android key APIs.
 */
internal class HeldKeyWatchdog(
    private val onDown: (HeldRemoteKey, Long) -> String,
    private val onRepeat: (HeldRemoteKey, Long, Int) -> Unit,
    private val onUp: (HeldRemoteKey, Long) -> Unit,
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() },
    private val watchdogTimeoutMs: Long = HeldInputProtocol.WATCHDOG_TIMEOUT_MS,
    private val repeatInitialDelayMs: Long = DEFAULT_REPEAT_INITIAL_DELAY_MS,
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
    /** Test seam for coordinating the registration-to-dispatch race; production default is a no-op. */
    private val afterHoldPublished: (String) -> Unit = {},
    /** Test seam called after a hold leaves the active map but before release acquires its lock. */
    private val beforeReleaseLock: (String) -> Unit = {}
) : AutoCloseable {
    private companion object {
        const val MAX_ACTIVE_HOLDS = 8
        const val DEFAULT_REPEAT_INITIAL_DELAY_MS = 500L
        const val DEFAULT_REPEAT_INTERVAL_MS = 250L
    }

    private class ActiveHold(
        val key: HeldRemoteKey,
        val downTime: Long
    ) {
        @Volatile var timeout: Job? = null
        var repeatJob: Job? = null
        var repeatCount: Int = 0
        var downDelivered: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<String, ActiveHold>()

    @Volatile private var closed = false

    fun handle(holdId: String, key: HeldRemoteKey, action: HeldAction): String {
        if (closed) return "closed"
        if (!HeldInputProtocol.isValidHoldId(holdId)) return "bad-hold"
        return when (action) {
            HeldAction.DOWN -> start(holdId, key)
            HeldAction.RENEW -> renew(holdId, key)
            HeldAction.UP -> {
                val existing = active[holdId]
                if (existing != null && existing.key != key) {
                    "bad-hold"
                } else {
                    release(holdId)
                    "ok"
                }
            }
        }
    }

    private fun start(holdId: String, key: HeldRemoteKey): String {
        val hold = ActiveHold(key, uptimeMillis())

        // Acquire the per-hold lock BEFORE publishing the hold. Otherwise UP/close can remove a
        // just-published hold, observe downDelivered=false, and return before DOWN is dispatched.
        // start would then dispatch DOWN with no remaining owner responsible for the matching UP.
        synchronized(hold) {
            synchronized(active) {
                if (closed) return "closed"
                active[holdId]?.let { existing ->
                    return if (existing.key == key) "ok" else "bad-hold"
                }
                if (active.size >= MAX_ACTIVE_HOLDS) return "busy"
                active[holdId] = hold
            }

            // Still inside synchronized(hold): a concurrent UP/close may remove the map entry,
            // but cannot observe downDelivered=false and return until DOWN has been dispatched.
            afterHoldPublished(holdId)

            val result = try {
                onDown(key, hold.downTime)
            } catch (_: Exception) {
                active.remove(holdId, hold)
                return "error"
            }
            if (result != "ok") {
                active.remove(holdId, hold)
                return result
            }

            hold.downDelivered = true
            scheduleTimeout(holdId, hold)
            if (isRepeatable(key)) {
                scheduleRepeats(holdId, hold)
            }
        }
        return "ok"
    }

    private fun renew(holdId: String, key: HeldRemoteKey): String {
        val hold = active[holdId] ?: return "expired"
        if (hold.key != key) return "bad-hold"
        synchronized(hold) {
            if (closed) return "closed"
            if (active[holdId] !== hold || !hold.downDelivered) return "expired"
            scheduleTimeout(holdId, hold)
        }
        return "ok"
    }

    private fun scheduleTimeout(holdId: String, hold: ActiveHold) {
        hold.timeout?.cancel()
        lateinit var timeout: Job
        timeout = scope.launch {
            delay(watchdogTimeoutMs)
            var shouldRelease = false
            synchronized(hold) {
                // A stale timeout must not release a hold whose lease has since been renewed.
                if (active[holdId] === hold && hold.timeout === timeout) {
                    active.remove(holdId, hold)
                    hold.timeout = null
                    hold.repeatJob?.cancel()
                    hold.repeatJob = null
                    shouldRelease = hold.downDelivered
                    hold.downDelivered = false
                }
            }
            if (shouldRelease) {
                try {
                    onUp(hold.key, hold.downTime)
                } catch (_: Exception) {
                    // One failed release must not stop other held-key timeouts.
                }
            }
        }
        hold.timeout = timeout
    }

    /**
     * Repeat a network-held media key or vertical navigation action.
     * Lease renewal remains independent: RENEW only extends the safety deadline.
     */
    private fun isRepeatable(key: HeldRemoteKey): Boolean =
        key == HeldRemoteKey.FORWARD || key == HeldRemoteKey.REWIND ||
            key == HeldRemoteKey.UP || key == HeldRemoteKey.DOWN

    private fun scheduleRepeats(holdId: String, hold: ActiveHold) {
        hold.repeatJob?.cancel()
        hold.repeatJob = scope.launch {
            delay(repeatInitialDelayMs)
            while (true) {
                val shouldContinue = synchronized(hold) {
                    if (closed || active[holdId] !== hold || !hold.downDelivered) {
                        false
                    } else {
                        val nextCount = hold.repeatCount + 1
                        try {
                            onRepeat(hold.key, hold.downTime, nextCount)
                            hold.repeatCount = nextCount
                            true
                        } catch (_: Exception) {
                            // Stop repeating after a dispatch failure; the UP/watchdog path still owns release.
                            false
                        }
                    }
                }
                if (!shouldContinue) break
                delay(repeatIntervalMs)
            }
        }
    }

    private fun release(holdId: String) {
        val hold = active.remove(holdId) ?: return
        beforeReleaseLock(holdId)
        synchronized(hold) {
            hold.timeout?.cancel()
            hold.timeout = null
            hold.repeatJob?.cancel()
            hold.repeatJob = null
            if (!hold.downDelivered) return
            hold.downDelivered = false
            try {
                onUp(hold.key, hold.downTime)
            } catch (_: Exception) {
                // One failed release must not stop other held-key timeouts.
            }
        }
    }

    override fun close() {
        synchronized(active) {
            closed = true
        }
        active.keys.toList().forEach(::release)
        scope.cancel()
    }
}
