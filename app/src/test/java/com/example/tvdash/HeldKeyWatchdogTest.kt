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
            onRepeat = { _, _, _ -> },
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
    fun mediaHoldRepeatsWithIncreasingCountsAndStopsOnUp() = runBlocking {
        val repeats = CopyOnWriteArrayList<Triple<HeldRemoteKey, Long, Int>>()
        val ups = CopyOnWriteArrayList<Pair<HeldRemoteKey, Long>>()
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ -> "ok" },
            onRepeat = { key, downTime, count -> repeats.add(Triple(key, downTime, count)) },
            onUp = { key, downTime -> ups.add(key to downTime) },
            uptimeMillis = { 123L },
            watchdogTimeoutMs = 1_000L,
            repeatInitialDelayMs = 30L,
            repeatIntervalMs = 25L
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"

        try {
            assertEquals("ok", watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN))
            delay(115L)
            assertTrue("expected repeated media key DOWN events", repeats.size >= 2)
            assertEquals(listOf(1, 2, 3).take(repeats.size), repeats.map { it.third }.take(3))
            assertTrue(repeats.all { it.first == HeldRemoteKey.FORWARD && it.second == 123L })

            assertEquals("ok", watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.UP))
            val countAtRelease = repeats.size
            delay(80L)
            assertEquals("no repeats may occur after UP", countAtRelease, repeats.size)
            assertEquals(listOf(HeldRemoteKey.FORWARD to 123L), ups)
        } finally {
            watchdog.close()
        }
    }

    @Test
    fun upCannotFinishBetweenHoldPublicationAndDownDispatch() {
        val published = CountDownLatch(1)
        val allowDown = CountDownLatch(1)
        val releaseReached = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ -> events.add("down"); "ok" },
            onRepeat = { _, _, _ -> },
            onUp = { _, _ -> events.add("up") },
            uptimeMillis = { 123L },
            watchdogTimeoutMs = 5_000L,
            afterHoldPublished = {
                published.countDown()
                assertTrue("test did not release DOWN dispatch", allowDown.await(10, TimeUnit.SECONDS))
            },
            beforeReleaseLock = {
                releaseReached.countDown()
                assertTrue("test did not release UP", allowRelease.await(10, TimeUnit.SECONDS))
            }
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"

        try {
            val down = executor.submit<String> {
                watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN)
            }
            assertTrue("hold was not published", published.await(10, TimeUnit.SECONDS))
            val up = executor.submit<String> {
                watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.UP)
            }
            assertTrue("UP did not reach the post-removal boundary", releaseReached.await(10, TimeUnit.SECONDS))
            assertTrue("UP returned before DOWN dispatch was allowed", !up.isDone)

            allowDown.countDown()
            allowRelease.countDown()
            assertEquals("ok", down.get(10, TimeUnit.SECONDS))
            assertEquals("ok", up.get(10, TimeUnit.SECONDS))
            assertEquals(listOf("down", "up"), events)
        } finally {
            allowDown.countDown()
            allowRelease.countDown()
            watchdog.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeCannotFinishBetweenHoldPublicationAndDownDispatch() {
        val published = CountDownLatch(1)
        val allowDown = CountDownLatch(1)
        val releaseReached = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ -> events.add("down"); "ok" },
            onRepeat = { _, _, _ -> },
            onUp = { _, _ -> events.add("up") },
            uptimeMillis = { 123L },
            watchdogTimeoutMs = 5_000L,
            afterHoldPublished = {
                published.countDown()
                assertTrue("test did not release DOWN dispatch", allowDown.await(10, TimeUnit.SECONDS))
            },
            beforeReleaseLock = {
                releaseReached.countDown()
                assertTrue("test did not release shutdown", allowRelease.await(10, TimeUnit.SECONDS))
            }
        )
        val id = "c0ffee00-0000-4000-8000-000000000001"

        try {
            val down = executor.submit<String> {
                watchdog.handle(id, HeldRemoteKey.FORWARD, HeldAction.DOWN)
            }
            assertTrue("hold was not published", published.await(10, TimeUnit.SECONDS))
            val close = executor.submit { watchdog.close() }
            assertTrue("shutdown did not reach hold release", releaseReached.await(10, TimeUnit.SECONDS))
            assertTrue("shutdown returned before DOWN dispatch was allowed", !close.isDone)

            allowDown.countDown()
            allowRelease.countDown()
            assertEquals("ok", down.get(10, TimeUnit.SECONDS))
            close.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("down", "up"), events)
        } finally {
            allowDown.countDown()
            allowRelease.countDown()
            watchdog.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun renewalRejectsWrongKeyAndUnknownHold() {
        val watchdog = HeldKeyWatchdog(
            onDown = { _, _ -> "ok" },
            onRepeat = { _, _, _ -> },
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
