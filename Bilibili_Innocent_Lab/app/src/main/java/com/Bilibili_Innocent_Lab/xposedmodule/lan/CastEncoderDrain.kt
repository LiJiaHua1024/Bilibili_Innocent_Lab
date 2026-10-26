package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One owner drains compressed output and interleaves audio while the GL worker submits frames. */
internal class CastEncoderDrain(private val encoder: MediaCodec, private val muxer: MediaMuxer,
    private val audioTrack: Int, private val audio: MediaExtractor, private val cancelled: AtomicBoolean,
    private val metrics: CastPreparationMetrics, private val progress: (String) -> Unit) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable>()
    private val worker = Thread(::drain, "BIL-Cast-Output").apply { start() }

    fun checkHealth() {
        failure.get()?.let { throw it }
        check(!cancelled.get()) { "media-preparation-cancelled" }
    }

    fun awaitCompletion() {
        while (worker.isAlive) {
            worker.join(100)
            checkHealth()
        }
        checkHealth()
    }

    private fun drain() {
        var started = false
        try {
            val encoded = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val audioBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            var audioDone = false
            fun copyAudio(untilUs: Long) {
                val begin = System.nanoTime()
                try {
                    while (!audioDone) {
                        check(!stopped.get() && !cancelled.get()) { "media-preparation-cancelled" }
                        val time = audio.sampleTime
                        // AAC priming may have a negative PTS. Only the extractor's end marker is EOF.
                        if (time == -1L && audio.sampleTrackIndex < 0) { audioDone = true; break }
                        if (time > untilUs) break
                        audioBuffer.clear()
                        val size = audio.readSampleData(audioBuffer, 0)
                        if (size < 0) { audioDone = true; break }
                        require(size <= audioBuffer.capacity())
                        audioInfo.set(0, size, time, 0)
                        muxer.writeSampleData(audioTrack, audioBuffer, audioInfo)
                        audio.advance()
                    }
                } finally { metrics.audioNs += System.nanoTime() - begin }
            }
            var videoTrack = -1
            var ended = false
            var lastProgress = -1L
            var lastOutput = System.nanoTime()
            while (!ended) {
                check(!stopped.get() && !cancelled.get()) { "media-preparation-cancelled" }
                val slot = encoder.dequeueOutputBuffer(encoded, 10_000)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started)
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); started = true
                    lastOutput = System.nanoTime()
                } else if (slot >= 0) {
                    try {
                        if (encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encoded.size > 0) {
                            check(started)
                            copyAudio(encoded.presentationTimeUs)
                            val buffer = checkNotNull(encoder.getOutputBuffer(slot))
                            buffer.position(encoded.offset); buffer.limit(encoded.offset + encoded.size)
                            val begin = System.nanoTime()
                            muxer.writeSampleData(videoTrack, buffer, encoded)
                            metrics.muxWriteNs += System.nanoTime() - begin
                            val seconds = encoded.presentationTimeUs / 1_000_000
                            if (seconds / 5 != lastProgress) { lastProgress = seconds / 5; progress("已合成 ${seconds} 秒弹幕视频") }
                        }
                        ended = encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(slot, false) }
                    lastOutput = System.nanoTime()
                }
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "encoder-stalled" }
            }
            check(started)
            copyAudio(Long.MAX_VALUE)
            check(!stopped.get() && !cancelled.get()) { "media-preparation-cancelled" }
            val begin = System.nanoTime()
            muxer.stop(); started = false
            metrics.finalizeNs = System.nanoTime() - begin
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
            // Release Surface backpressure if GL is inside eglSwapBuffers during cancellation/failure.
            runCatching { encoder.stop() }
        } finally {
            if (started) runCatching { muxer.stop() }
        }
    }

    override fun close() {
        stopped.set(true)
        var interrupted = false
        // The caller cannot release the muxer/extractor while the output owner still uses them.
        while (worker.isAlive) {
            try { worker.join(100) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
