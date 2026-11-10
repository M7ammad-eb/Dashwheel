package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IdleGuardTest {

    @Test
    fun quietLinkIsClosedOnce() {
        val closed = CountDownLatch(1)
        val calls = AtomicInteger()
        IdleGuard(timeoutMs = 60, onIdle = { calls.incrementAndGet(); closed.countDown() }, tickMs = 10).start()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(1, calls.get())
    }

    @Test
    fun hearingFromTheOtherEndKeepsItOpen() {
        // The guard's clock is the test's: time passes when the test says, so a slow runner changes nothing.
        val now = AtomicLong(1_000)
        val reads = AtomicInteger()
        val closed = CountDownLatch(1)
        val guard = IdleGuard(timeoutMs = 120, onIdle = { closed.countDown() }, now = { reads.incrementAndGet(); now.get() }, tickMs = 5).start()
        repeat(15) {
            now.addAndGet(30)
            guard.kick()
            // The guard looks at the clock at least once between kicks: a kick every 30 ms, a timeout of 120.
            val seen = reads.get()
            val until = System.currentTimeMillis() + 2_000
            while (reads.get() == seen && System.currentTimeMillis() < until) Thread.sleep(1)
            assertTrue("the guard stopped looking", reads.get() > seen)
        }
        assertEquals(1L, closed.count)
        // Left alone past the timeout, it closes: the clock above was really the guard's.
        now.addAndGet(121)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun stoppedGuardNeverCloses() {
        val closed = CountDownLatch(1)
        val guard = IdleGuard(timeoutMs = 40, onIdle = { closed.countDown() }, tickMs = 10).start()
        guard.stop()
        assertFalse(closed.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun timeoutCanBeChangedWhileRunning() {
        val closed = CountDownLatch(1)
        val guard = IdleGuard(timeoutMs = 60_000, onIdle = { closed.countDown() }, tickMs = 10).start()
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        guard.timeoutMs = 20
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }
}
