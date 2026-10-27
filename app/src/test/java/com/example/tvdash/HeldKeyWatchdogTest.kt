package com.example.tvdash

import com.example.tvlink.HeldAction
import com.example.tvlink.HeldRemoteKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeldKeyWatchdogTest {
    @Test
    fun renewExtendsLeaseWithoutSendingAnotherDown() = runBlocking {
        val downs = CopyOnWriteArrayList<Pair<HeldRemoteKey, Long>>()
        val ups = CopyOnWriteArrayList<Pair<HeldRemoteKey, Long>>()
        val watchdog = HeldKeyWatchdog(
            onDown = { key, downTime -> downs.add(key to downTime); "ok" },
            onUp = { key, downTime -> ups.add(key to downTime) },
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
    fun upDuringDownDispatchStillPairsDownWithExactlyOneUp() {
        val downEntered = CountDownLatch(1)
        val finishDown = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ ->
                downEntered.countDown()
                assertTrue("test did not release DOWN callback", finishDown.await(10, TimeUnit.SECONDS))
                events.add("down")
                "ok"
            },
            onUp = { _, _ -> events.add("up") },
            watchdogTimeoutMs = 5_000L
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"

        try {
            val down = executor.submit<String> {
                watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN)
            }
            assertTrue(downEntered.await(10, TimeUnit.SECONDS))
            val upStarted = CountDownLatch(1)
            val up = executor.submit<String> {
                upStarted.countDown()
                watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.UP)
            }
            assertTrue(upStarted.await(10, TimeUnit.SECONDS))
            finishDown.countDown()

            assertEquals("ok", down.get(10, TimeUnit.SECONDS))
            assertEquals("ok", up.get(10, TimeUnit.SECONDS))
            assertEquals(listOf("down", "up"), events)
        } finally {
            finishDown.countDown()
            watchdog.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeDuringDownDispatchStillPairsDownWithExactlyOneUp() {
        val downEntered = CountDownLatch(1)
        val finishDown = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ ->
                downEntered.countDown()
                assertTrue("test did not release DOWN callback", finishDown.await(10, TimeUnit.SECONDS))
                events.add("down")
                "ok"
            },
            onUp = { _, _ -> events.add("up") },
            watchdogTimeoutMs = 5_000L
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"

        try {
            val down = executor.submit<String> {
                watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN)
            }
            assertTrue(downEntered.await(10, TimeUnit.SECONDS))
            val closeStarted = CountDownLatch(1)
            val close = executor.submit {
                closeStarted.countDown()
                watchdog.close()
            }
            assertTrue(closeStarted.await(10, TimeUnit.SECONDS))
            finishDown.countDown()

            assertEquals("ok", down.get(10, TimeUnit.SECONDS))
            close.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("down", "up"), events)
        } finally {
            finishDown.countDown()
            watchdog.close()
            executor.shutdownNow()
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
