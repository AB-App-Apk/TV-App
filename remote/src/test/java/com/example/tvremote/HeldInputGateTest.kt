package com.example.tvremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldInputGateTest {
    @Test
    fun ordinaryInputWaitsUntilHeldInputIsReleased() = runBlocking {
        val gate = HeldInputGate()
        val lease = gate.acquireHold()
        val entered = CompletableDeferred<Unit>()
        val command = launch {
            gate.withInput {
                entered.complete(Unit)
                "sent"
            }
        }

        yield()
        assertFalse("ordinary input must not pass an active hold", entered.isCompleted)

        lease.release()
        assertEquals("sent", command.await())
        assertTrue(entered.isCompleted)
        lease.release() // Duplicate cleanup must be harmless.
    }

    @Test
    fun heldInputWaitsForAnOrdinaryCommandToFinish() = runBlocking {
        val gate = HeldInputGate()
        val commandEntered = CompletableDeferred<Unit>()
        val finishCommand = CompletableDeferred<Unit>()
        val command = launch {
            gate.withInput {
                commandEntered.complete(Unit)
                finishCommand.await()
            }
        }
        commandEntered.await()

        val holdAcquired = CompletableDeferred<HeldInputGate.HoldLease>()
        val hold = launch { holdAcquired.complete(gate.acquireHold()) }
        yield()
        assertFalse("a hold must not overlap an in-flight command", holdAcquired.isCompleted)

        finishCommand.complete(Unit)
        command.join()
        val lease = holdAcquired.await()
        assertTrue(hold.isCompleted)
        lease.release()
    }
}
