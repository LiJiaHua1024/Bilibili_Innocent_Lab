package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codec-neutral track description shared by every output container.
 * [csd] holds the codec configuration buffers (csd-0, csd-1, ...); [platform] carries the original Android
 * MediaFormat and is read only by the MediaMuxer adapter, so the TS path stays testable on the JVM.
 */
internal class CastTrackFormat(val mime: String, val csd: List<ByteArray> = emptyList(),
    val width: Int = 0, val height: Int = 0, val platform: Any? = null) {
    val video get() = mime.startsWith("video/")
}

/**
 * Compressed-sample output. One owner thread calls addTrack…, start, write…, finish in this order.
 * The [write] buffer (position..limit) is borrowed from a codec or extractor and is valid only during the
 * call; an implementation must consume or copy it before returning and must not retain the reference.
 */
internal interface CastSampleSink {
    fun addTrack(format: CastTrackFormat): Int
    fun start()
    fun write(track: Int, data: ByteBuffer, timeUs: Long, key: Boolean)
    fun finish()
    /** Best-effort cleanup after failure or cancellation; partial output is never published as complete. */
    fun abort() {}
}

/** Pull-side compressed samples (MediaExtractor on the device, synthetic data in tests). */
internal interface CastSampleReader {
    /** Presentation time of the current sample, or null at the end of the stream. */
    fun peekTimeUs(): Long?
    val key: Boolean
    /** Copies the current sample into the cleared [buffer] and returns its size, or a negative value at the end. */
    fun read(buffer: ByteBuffer): Int
    fun advance()
}

/** Set while output deliberately waits for a slow or paused receiver, so codec stall watchdogs do not misfire. */
internal class CastFlowHold {
    @Volatile var held = false
}

/**
 * One cast session's clock. Output time = source time − [offsetUs]; the offset is fixed by the first production
 * pass and reused by later passes so resumed segments continue the same timestamps.
 */
internal class CastTimeline(val styleVersion: Int = 0) {
    @Volatile var offsetUs: Long? = null
        private set
    @Synchronized fun fix(sourceStartUs: Long): Long = offsetUs ?: sourceStartUs.also {
        require(it >= 0) { "invalid-start" }
        offsetUs = it
    }
    fun sourceUs(outputUs: Long): Long = outputUs + (offsetUs ?: 0)
    fun outputUs(sourceUs: Long): Long = sourceUs - (offsetUs ?: 0)
}

/** Maps source time to output time and, for a non-zero session start, drops samples before [startUs]. */
internal class CastShiftedReader(private val source: CastSampleReader, private val startUs: Long,
    private val offsetUs: Long) : CastSampleReader {
    override fun peekTimeUs(): Long? {
        while (true) {
            val time = source.peekTimeUs() ?: return null
            // A zero start keeps AAC priming samples with negative PTS, matching the complete-MP4 path.
            if (startUs <= 0 || time >= startUs) return time - offsetUs
            source.advance()
        }
    }
    override val key get() = source.key
    override fun read(buffer: ByteBuffer) = source.read(buffer)
    override fun advance() = source.advance()
}

/** Passthrough remux: both tracks are written in time order so streaming segments receive interleaved A/V. */
internal object CastInterleave {
    fun copy(video: CastSampleReader, videoTrack: Int, audio: CastSampleReader, audioTrack: Int,
        sink: CastSampleSink, cancelled: AtomicBoolean, capacity: Int = 8 * 1024 * 1024, written: (Int) -> Unit = {}) {
        val buffer = ByteBuffer.allocateDirect(capacity)
        var videoDone = false
        var audioDone = false
        while (true) {
            check(!cancelled.get()) { "media-preparation-cancelled" }
            val videoTime = if (videoDone) null else video.peekTimeUs()
            val audioTime = if (audioDone) null else audio.peekTimeUs()
            if (videoTime == null && audioTime == null) return
            // Ties go to video so a segment that starts at a key frame opens before same-time audio arrives.
            val useVideo = audioTime == null || videoTime != null && videoTime <= audioTime
            val reader = if (useVideo) video else audio
            buffer.clear()
            val size = reader.read(buffer)
            if (size < 0) {
                // Only the reader's own end marker ends a track; a negative read means it has nothing left.
                if (useVideo) videoDone = true else audioDone = true
                continue
            }
            require(size <= buffer.capacity()) { "sample-too-large" }
            buffer.position(0); buffer.limit(size)
            sink.write(if (useVideo) videoTrack else audioTrack, buffer, checkNotNull(if (useVideo) videoTime else audioTime),
                useVideo && reader.key)
            written(size)
            reader.advance()
        }
    }
}
