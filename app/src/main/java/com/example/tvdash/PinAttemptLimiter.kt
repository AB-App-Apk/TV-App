package com.example.tvdash

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Per-client throttle for the TV's shared PIN. The wire response stays the same for rejected and
 * rate-limited requests; the distinction is kept inside the server only.
 */
internal enum class PinAuthResult {
    ACCEPTED,
    REJECTED,
    RATE_LIMITED
}

internal class PinAttemptLimiter(
    private val maxFailures: Int = 5,
    private val failureWindowMs: Long = 60_000L,
    private val initialCooldownMs: Long = 60_000L,
    private val maxCooldownMs: Long = 60 * 60_000L,
    private val maxTrackedClients: Int = 256,
    private val idleResetMs: Long = 24 * 60 * 60_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class State(
        var failures: Int,
        var windowStartedAtMs: Long,
        var lastActivityAtMs: Long,
        var lockouts: Int = 0,
        var blockedUntilMs: Long = 0L
    )

    private val lock = Any()

    // Access-order bounds memory use and lets a new client evict the least-recently-used entry.
    private val clients = LinkedHashMap<String, State>(16, 0.75f, true)

    init {
        require(maxFailures > 0)
        require(failureWindowMs > 0L)
        require(initialCooldownMs > 0L)
        require(maxCooldownMs >= initialCooldownMs)
        require(maxTrackedClients > 0)
        require(idleResetMs > 0L)
    }

    fun authenticate(clientKey: String, suppliedPin: String?, expectedPin: String): PinAuthResult {
        val now = nowMs()
        val key = clientKey.ifBlank { "unknown" }

        synchronized(lock) {
            removeIdleClients(now)
            var state = clients[key]

            // Do not allow even a correct PIN to bypass an active cooldown.
            if (state != null && state.blockedUntilMs > now) {
                return PinAuthResult.RATE_LIMITED
            }

            // Preserve the escalation level after cooldown; reset only the current failure window.
            if (state != null && state.blockedUntilMs > 0L) {
                state.blockedUntilMs = 0L
                state.failures = 0
                state.windowStartedAtMs = now
                state.lastActivityAtMs = now
            }

            if (suppliedPin != null && MessageDigest.isEqual(
                    suppliedPin.toByteArray(Charsets.UTF_8),
                    expectedPin.toByteArray(Charsets.UTF_8)
                )
            ) {
                clients.remove(key)
                return PinAuthResult.ACCEPTED
            }

            if (state == null) {
                if (clients.size >= maxTrackedClients) {
                    // Never evict an active cooldown to make room for a new client identity.
                    val iterator = clients.entries.iterator()
                    var evicted = false
                    while (iterator.hasNext()) {
                        if (iterator.next().value.blockedUntilMs <= now) {
                            iterator.remove()
                            evicted = true
                            break
                        }
                    }
                    if (!evicted) return PinAuthResult.RATE_LIMITED
                }
                state = State(failures = 0, windowStartedAtMs = now, lastActivityAtMs = now)
                clients[key] = state
            }

            if (now - state.windowStartedAtMs > failureWindowMs) {
                state.failures = 0
                state.windowStartedAtMs = now
            }

            state.failures++
            state.lastActivityAtMs = now

            if (state.failures >= maxFailures) {
                state.lockouts++
                state.failures = 0
                state.blockedUntilMs = now + cooldownFor(state.lockouts)
                return PinAuthResult.RATE_LIMITED
            }

            return PinAuthResult.REJECTED
        }
    }

    private fun cooldownFor(lockouts: Int): Long {
        val exponent = (lockouts - 1).coerceIn(0, 10)
        val multiplier = 1L shl exponent
        return if (initialCooldownMs > maxCooldownMs / multiplier) {
            maxCooldownMs
        } else {
            (initialCooldownMs * multiplier).coerceAtMost(maxCooldownMs)
        }
    }

    private fun removeIdleClients(now: Long) {
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            val cooldownFinished = state.blockedUntilMs == 0L || state.blockedUntilMs <= now
            if (cooldownFinished && now - state.lastActivityAtMs > idleResetMs) {
                iterator.remove()
            }
        }
    }
}
