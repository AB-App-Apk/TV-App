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
 * Owns network-originated held keys. Every DOWN is paired with an UP either from the phone or after four seconds.
 * The callback functions are deliberately injected so this class does not depend on Android key-injection APIs.
 */
internal class HeldKeyWatchdog(
    private val onDown: (HeldRemoteKey, Long) -> String,
    private val onUp: (HeldRemoteKey, Long) -> Unit
) : AutoCloseable {
    private companion object {
        const val MAX_ACTIVE_HOLDS = 8
    }

    private class ActiveHold(
        val key: HeldRemoteKey,
        val downTime: Long
    ) {
        @Volatile var timeout: Job? = null
        var downDelivered: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<String, ActiveHold>()

    fun handle(holdId: String, key: HeldRemoteKey, action: HeldAction): String {
        if (!HeldInputProtocol.isValidHoldId(holdId)) return "bad-hold"
        return when (action) {
            HeldAction.DOWN -> start(holdId, key)
            HeldAction.UP -> {
                release(holdId)
                "ok"
            }
        }
    }

    private fun start(holdId: String, key: HeldRemoteKey): String {
        active[holdId]?.let { existing ->
            return if (existing.key == key) "ok" else "bad-hold"
        }
        if (active.size >= MAX_ACTIVE_HOLDS) return "busy"

        val hold = ActiveHold(key, SystemClock.uptimeMillis())
        val existing = active.putIfAbsent(holdId, hold)
        if (existing != null) return if (existing.key == key) "ok" else "bad-hold"

        synchronized(hold) {
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
            val timeout = scope.launch {
                delay(HeldInputProtocol.WATCHDOG_TIMEOUT_MS)
                release(holdId)
            }
            hold.timeout = timeout
            // An UP can remove this entry while onDown is being handled; do not leave an orphan timeout.
            if (active[holdId] !== hold) timeout.cancel()
        }
        return "ok"
    }

    private fun release(holdId: String) {
        val hold = active.remove(holdId) ?: return
        synchronized(hold) {
            hold.timeout?.cancel()
            if (!hold.downDelivered) return
            try {
                onUp(hold.key, hold.downTime)
            } catch (_: Exception) {
                // One failed release must not stop other held-key timeouts.
            }
        }
    }

    override fun close() {
        active.keys.toList().forEach(::release)
        scope.cancel()
    }
}
