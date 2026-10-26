package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The limit applies to all retained tracks, including concurrent in-flight writes. */
internal class CastDownloadBudget(private val limit: Long) {
    private var claimed = 0L
    init { require(limit > 0) { "media-budget-exceeded" } }
    @Synchronized fun acquire(bytes: Int) {
        require(bytes >= 0 && bytes.toLong() <= limit - claimed) { "media-budget-exceeded" }
        claimed += bytes
    }
    @Synchronized fun release(bytes: Long) {
        require(bytes in 0..claimed)
        claimed -= bytes
    }
}

/** Avoid a filesystem query per packet/sample while retaining a conservative 64 MiB reserve. */
internal class CastWriteSpace(private val available: () -> Long, private val clock: () -> Long = System::nanoTime) {
    private var checkedAt: Long? = null
    private var allowance = 0L
    private var sinceCheck = 0L
    @Synchronized fun allows(bytes: Int): Boolean {
        require(bytes >= 0)
        val now = clock()
        val previous = checkedAt
        if (previous == null || sinceCheck + bytes >= WINDOW || now - previous >= 250_000_000L) {
            // Extra headroom covers the bounded writes between probes and both active download workers.
            allowance = available() - RESERVE - WINDOW
            sinceCheck = 0
            checkedAt = now
        }
        if (bytes.toLong() > allowance) return false
        allowance -= bytes
        sinceCheck += bytes
        return true
    }
    private companion object {
        const val RESERVE = 64L * 1024 * 1024
        const val WINDOW = 1024L * 1024
    }
}

/** At most two track transfers; returning means every writer has exited, including on cancellation. */
internal object CastParallelTracks {
    fun download(count: Int, cancelled: AtomicBoolean, disconnect: () -> Unit,
        progress: (Int) -> Unit, transfer: (Int) -> Long): Long {
        require(count in 1..16)
        check(!cancelled.get())
        val cursor = AtomicInteger()
        val completed = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val bytes = LongArray(count)
        val workers = minOf(2, count)
        val done = CountDownLatch(workers)
        val threads = List(workers) { worker ->
            Thread({
                try {
                    while (!cancelled.get()) {
                        val index = cursor.getAndIncrement()
                        if (index >= count) break
                        bytes[index] = transfer(index)
                        completed.incrementAndGet()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    cancelled.set(true)
                    runCatching(disconnect)
                } finally { done.countDown() }
            }, "BIL-Cast-Download-$worker").apply { isDaemon = true }
        }
        var interrupted = false
        var previous = -1
        var started = 0
        try {
            try { threads.forEach { it.start(); started++ } }
            catch (error: Throwable) {
                repeat(workers - started) { done.countDown() }
                throw error
            }
            while (true) {
                if (cancelled.get()) {
                    threads.forEach(Thread::interrupt)
                    runCatching(disconnect)
                }
                try {
                    val finished = done.await(100, TimeUnit.MILLISECONDS)
                    val current = completed.get()
                    if (current != previous && !cancelled.get()) { previous = current; progress(current) }
                    if (finished) break
                } catch (_: InterruptedException) {
                    interrupted = true
                    cancelled.set(true)
                }
            }
        } finally {
            // A callback can fail too. Never let prepare delete inputs while a worker still writes them.
            if (done.count > 0) {
                cancelled.set(true)
                threads.forEach(Thread::interrupt)
                runCatching(disconnect)
                while (done.count > 0) {
                    try { done.await(100, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure.get()?.let { throw it }
        check(!cancelled.get()) { "media-preparation-cancelled" }
        return bytes.sum()
    }
}
