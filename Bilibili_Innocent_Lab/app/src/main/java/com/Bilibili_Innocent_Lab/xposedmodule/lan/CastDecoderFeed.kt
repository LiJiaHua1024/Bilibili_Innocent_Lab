package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.media.MediaCodec
import android.media.MediaExtractor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Input extraction overlaps GL work; codec-owned buffers provide the queue bound and backpressure. */
internal class CastDecoderFeed(private val decoder: MediaCodec, private val source: MediaExtractor,
    private val cancelled: AtomicBoolean, private val metrics: CastPreparationMetrics,
    private val hold: CastFlowHold = CastFlowHold()) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable>()
    private val worker = Thread(::feed, "BIL-Cast-Input").apply { start() }

    fun checkHealth() {
        failure.get()?.let { throw it }
        check(!cancelled.get()) { "media-preparation-cancelled" }
    }

    private fun feed() {
        try {
            var lastInput = System.nanoTime()
            while (!stopped.get() && !cancelled.get()) {
                val slot = decoder.dequeueInputBuffer(10_000)
                if (slot < 0) {
                    // A paused receiver legitimately fills every codec buffer; that is not a stalled decoder.
                    if (hold.held) lastInput = System.nanoTime()
                    check(System.nanoTime() - lastInput < 30_000_000_000L) { "decoder-input-stalled" }
                    continue
                }
                val begin = System.nanoTime()
                try {
                    val buffer = checkNotNull(decoder.getInputBuffer(slot)).apply { clear() }
                    val count = source.readSampleData(buffer, 0)
                    if (count < 0) {
                        decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                    require(count <= buffer.capacity())
                    decoder.queueInputBuffer(slot, 0, count, source.sampleTime, 0)
                    source.advance()
                } finally { metrics.inputFeedNs += System.nanoTime() - begin }
                lastInput = System.nanoTime()
            }
        } catch (error: Throwable) { failure.compareAndSet(null, error) }
    }

    override fun close() {
        stopped.set(true)
        var interrupted = false
        while (worker.isAlive) {
            try { worker.join(100) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
