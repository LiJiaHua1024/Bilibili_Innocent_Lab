package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CastDownloadResourcesTest {
    @Test fun `two workers overlap but no third transfer runs concurrently`() {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val firstPair = CountDownLatch(2)
        val visits = IntArray(8)
        val owner = Thread.currentThread()
        val bytes = CastParallelTracks.download(8, AtomicBoolean(false), {}, {
            assertSame(owner, Thread.currentThread())
        }) { index ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try {
                if (index < 2) { firstPair.countDown(); assertTrue(firstPair.await(5, TimeUnit.SECONDS)) }
                visits[index]++
                (index + 1).toLong()
            } finally { active.decrementAndGet() }
        }
        assertEquals(36L, bytes); assertEquals(2, maximum.get()); assertEquals(0, active.get())
        assertArrayEquals(IntArray(8) { 1 }, visits)
    }
    @Test fun `second track failure cancels and joins the first writer before returning`() {
        val cancelled = AtomicBoolean(false)
        val entered = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val writerExited = AtomicBoolean(false)
        val failure = runCatching {
            CastParallelTracks.download(2, cancelled, { disconnected.countDown() }, {}) { index ->
                if (index == 0) {
                    entered.countDown()
                    try { assertTrue(disconnected.await(5, TimeUnit.SECONDS)); 10L }
                    finally { writerExited.set(true) }
                } else {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    error("second-track-failed")
                }
            }
        }.exceptionOrNull()
        assertEquals("second-track-failed", failure?.message)
        assertTrue(cancelled.get()); assertTrue(writerExited.get())
    }
    @Test fun `cancellation before startup performs no transfer`() {
        var invoked = false
        assertTrue(runCatching {
            CastParallelTracks.download(2, AtomicBoolean(true), {}, {}) { invoked = true; 1L }
        }.isFailure)
        assertFalse(invoked)
    }
    @Test fun `external cancellation disconnects and joins both active writers`() {
        val cancelled = AtomicBoolean(false)
        val entered = CountDownLatch(2); val disconnected = CountDownLatch(1)
        val exited = AtomicInteger(); val failure = AtomicReference<Throwable>()
        val owner = Thread {
            try {
                CastParallelTracks.download(2, cancelled, { disconnected.countDown() }, {}) {
                    entered.countDown()
                    try { assertTrue(disconnected.await(5, TimeUnit.SECONDS)); 1L }
                    finally { exited.incrementAndGet() }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        owner.start()
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); cancelled.set(true) }
        finally { cancelled.set(true); owner.join(5000) }
        assertFalse(owner.isAlive); assertNotNull(failure.get()); assertEquals(2, exited.get())
    }
    @Test fun `progress callback failure still waits for active writers to exit`() {
        val entered = CountDownLatch(2); val disconnected = CountDownLatch(1)
        val exited = AtomicInteger()
        val failure = runCatching {
            CastParallelTracks.download(2, AtomicBoolean(false), { disconnected.countDown() }, {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                error("progress-failed")
            }) {
                entered.countDown()
                try { assertTrue(disconnected.await(5, TimeUnit.SECONDS)); 1L }
                finally { exited.incrementAndGet() }
            }
        }.exceptionOrNull()
        assertEquals("progress-failed", failure?.message); assertEquals(2, exited.get())
    }
    @Test fun `shared byte reservation is atomic across both active workers`() {
        val budget = CastDownloadBudget(100)
        val succeeded = AtomicInteger()
        val start = CountDownLatch(2)
        CastParallelTracks.download(2, AtomicBoolean(false), {}, {}) {
            start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
            if (runCatching { budget.acquire(60) }.isSuccess) succeeded.incrementAndGet()
            0L
        }
        assertEquals(1, succeeded.get())
        budget.acquire(40)
        assertTrue(runCatching { budget.acquire(1) }.isFailure)
    }
    @Test fun `space checks are amortized by bytes and refresh after elapsed time`() {
        val mebibyte = 1024L * 1024
        var probes = 0; var time = 0L
        var free = 512L * mebibyte
        val space = CastWriteSpace({ probes++; free }, { time })
        repeat(1000) { assertTrue(space.allows(64 * 1024)); free -= 64 * 1024 }
        assertTrue("filesystem checks=$probes", probes < 80)
        val previous = probes
        time += 250_000_000L
        assertTrue(space.allows(1)); assertEquals(previous + 1, probes)
        free = 65L * mebibyte
        time += 250_000_000L
        assertFalse(space.allows(1))
    }
    @Test fun `large writes must fit the free space reserve in their entirety`() {
        val mebibyte = 1024L * 1024
        val space = CastWriteSpace({ 70L * mebibyte }, { 0L })
        assertFalse(space.allows((6 * mebibyte).toInt()))
        assertTrue(space.allows((5 * mebibyte).toInt()))
    }
}
