package com.example.tvdash

import org.junit.Assert.assertEquals
import org.junit.Test

class PinAttemptLimiterTest {
    private var now = 0L

    private fun limiter() = PinAttemptLimiter(nowMs = { now })

    @Test
    fun repeatedWrongPinsTriggerCooldownAndCorrectPinCannotBypassIt() {
        val limiter = limiter()

        repeat(4) {
            assertEquals(PinAuthResult.REJECTED, limiter.authenticate("wifi:192.168.1.10", "0000", "1234"))
        }
        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("wifi:192.168.1.10", "0000", "1234"))
        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("wifi:192.168.1.10", "1234", "1234"))

        now += 60_001L
        assertEquals(PinAuthResult.ACCEPTED, limiter.authenticate("wifi:192.168.1.10", "1234", "1234"))
    }

    @Test
    fun cooldownIsIsolatedPerClient() {
        val limiter = limiter()

        repeat(5) {
            limiter.authenticate("wifi:192.168.1.10", "wrong", "1234")
        }

        assertEquals(PinAuthResult.ACCEPTED, limiter.authenticate("bluetooth:AA:BB:CC:DD:EE:FF", "1234", "1234"))
        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("wifi:192.168.1.10", "1234", "1234"))
    }

    @Test
    fun failureWindowExpires() {
        val limiter = limiter()

        repeat(4) {
            assertEquals(PinAuthResult.REJECTED, limiter.authenticate("client", null, "1234"))
        }

        now += 60_001L
        repeat(4) {
            assertEquals(PinAuthResult.REJECTED, limiter.authenticate("client", "bad", "1234"))
        }
    }

    @Test
    fun successfulAuthenticationClearsFailureHistory() {
        val limiter = limiter()

        repeat(4) {
            assertEquals(PinAuthResult.REJECTED, limiter.authenticate("client", "bad", "1234"))
        }
        assertEquals(PinAuthResult.ACCEPTED, limiter.authenticate("client", "1234", "1234"))
        repeat(4) {
            assertEquals(PinAuthResult.REJECTED, limiter.authenticate("client", "bad", "1234"))
        }
        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("client", "bad", "1234"))
    }

    @Test
    fun cooldownEscalatesAfterRepeatedLockouts() {
        val limiter = limiter()
        repeat(5) { limiter.authenticate("client", "bad", "1234") }
        now += 60_001L
        repeat(5) { limiter.authenticate("client", "bad", "1234") }

        now += 60_001L
        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("client", "1234", "1234"))

        now += 60_001L
        assertEquals(PinAuthResult.ACCEPTED, limiter.authenticate("client", "1234", "1234"))
    }
    @Test
    fun activeCooldownCannotBeEvictedByNewClientIdentities() {
        val limiter = PinAttemptLimiter(maxTrackedClients = 1, nowMs = { now })
        repeat(5) { limiter.authenticate("first", "bad", "1234") }

        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("second", "bad", "1234"))
        assertEquals(PinAuthResult.ACCEPTED, limiter.authenticate("second", "1234", "1234"))
        assertEquals(PinAuthResult.RATE_LIMITED, limiter.authenticate("first", "1234", "1234"))
    }

}
