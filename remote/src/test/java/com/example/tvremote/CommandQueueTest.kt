package com.example.tvremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandQueueTest {
    @Test
    fun nextCommandWaitsUntilHeldInputReleaseCompletes() = runBlocking {
        val queue = CommandQueue(this)
        val events = mutableListOf<String>()
        val downStarted = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val okFinished = CompletableDeferred<Unit>()

        try {
            queue.submit {
                events += "ff-down"
                downStarted.complete(Unit)
                allowRelease.await()
                events += "ff-up"
            }
            queue.submit {
                events += "ok"
                okFinished.complete(Unit)
            }

            downStarted.await()
            assertFalse("OK must not overtake the FF/REW release", okFinished.isCompleted)

            allowRelease.complete(Unit)
            withTimeout(2_000L) { okFinished.await() }

            assertEquals(listOf("ff-down", "ff-up", "ok"), events)
        } finally {
            queue.close()
        }
    }
}
