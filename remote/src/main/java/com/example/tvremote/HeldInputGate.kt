package com.example.tvremote

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes all ordinary remote input against one physical held-key session.
 *
 * A hold owns the mutex across calls/coroutines until its lease is released. Release is idempotent so normal UP,
 * watchdog timeout, and shutdown can safely converge on the same cleanup path.
 */
internal class HeldInputGate {
    private val mutex = Mutex()

    internal class HoldLease internal constructor(private val unlock: () -> Unit) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) unlock()
        }
    }

    suspend fun <T> withInput(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun acquireHold(): HoldLease {
        mutex.lock()
        return HoldLease { mutex.unlock() }
    }
}
