package com.example.tvdash

import com.example.tvlink.HeldAction
import com.example.tvlink.HeldRemoteKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HeldKeyWatchdogTest {
    @Test
    fun renewExtendsLeaseWithoutSendingAnotherDown() = runBlocking {
        val downs = CopyOnWriteArrayList<Pair<HeldRemoteKey, Long>>()
        val ups = CopyOnWriteArrayList<Pair<HeldRemoteKey, Long>>()
        val watchdog = HeldKeyWatchdog(
            onDown = { key, downTime -> downs.add(key to downTime); "ok" },
            onUp = { key, downTime -> ups.add(key to downTime },
            uptimeMillis = { 123L },
            watchdogTimeoutMs = 400L
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"

        try {
            assertEquals("ok", watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN))
            delay(250L)
            assertEquals("ok", watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.RENEW))
            delay(220L) // Past the original deadline, but before the renewed deadline.
            assertTrue(ups.isEmpty())
            assertEquals(1, downs.size)

            delay(250L)
            assertEquals(listOf(HeldRemoteKey.FORWARD to 123L), downs)
            assertEquals(listOf(HeldRemoteKey.FORWARD to 123L), ups)
        } finally {
            watchdog.close()
        }
    }

    @Test
    fun renewalRejectsWrongKeyAndUnknownHold() {
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ -> "ok" },
            onUp = { _, _ -> },
            uptimeMillis = { 123L }
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"
        try {
            assertEquals("ok", watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN))
            assertEquals("bad-hold", watchdog.handle(id, HeldRemoteKey.REWIND, HeldAction.RENEW))
            assertEquals("expired", watchdog.handle("c0ffee00-0000-4000-8000-000000000002", HeldRemoteKey.FORWARD, HeldAction.RENEW))
        } finally {
            watchdog.close()
        }
    }
}
