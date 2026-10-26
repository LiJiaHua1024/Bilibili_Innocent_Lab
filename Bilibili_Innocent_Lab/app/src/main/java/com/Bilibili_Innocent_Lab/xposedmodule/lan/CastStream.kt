package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown inside production when a receiver stayed paused past the budget; [resumeOutputUs] starts the next pass. */
internal class CastSuspended(val resumeOutputUs: Long) : Exception("cast-production-suspended")

/** Bounds for one streaming session. Defaults are first-test values from the realtime evaluation, not promises. */
internal data class CastStreamConfig(
    val segmentUs: Long = 2_000_000,
    val targetDurationS: Int = 4,
    val startupUs: Long = 6_000_000,
    val maxAheadUs: Long = 120_000_000,
    val pauseBudgetNs: Long = 600_000_000_000L,
    val budgetBytes: Long = 2L * 1024 * 1024 * 1024,
    val readyTimeoutNs: Long = 180_000_000_000L,
    val segmentWaitNs: Long = 60_000_000_000L,
    val chunked: Boolean = false,
) {
    /** Every EXTINF rounds to at most the declared target: keep half a second plus one slow frame of margin. */
    val hardCutUs get() = targetDurationS * 1_000_000L - 600_000
    init { require(segmentUs in 500_000..hardCutUs && startupUs >= segmentUs && maxAheadUs >= startupUs) }
}

/**
 * Completed segments only. A segment becomes visible after its file is closed and renamed, so a playlist never
 * lists bytes that are still being written. Production blocks here when it is too far ahead of the receiver.
 */
internal class CastSegmentStore(private val directory: File, private val config: CastStreamConfig,
    private val hold: CastFlowHold, private val hasSpace: (Int) -> Boolean = { true },
    private val clock: () -> Long = System::nanoTime) : AutoCloseable {
    class Segment(val index: Int, val file: File, val startUs: Long, val durationUs: Long, val bytes: Long, val independent: Boolean)
    class Snapshot(val segments: List<Segment>, val finished: Boolean, val failure: String?)
    private val lock = Object() // Java monitor methods coordinate the producer with HTTP readers.
    private val segments = ArrayList<Segment>()
    private var finished = false
    private var failure: String? = null
    private var closed = false
    private var bytes = 0L
    private var open: OpenSegment? = null
    private var demandEndUs = 0L
    @Volatile var lastDemandNs = clock(); private set

    private inner class OpenSegment(val index: Int, val part: File, val startUs: Long, val independent: Boolean) {
        var written = 0L
        val stream: OutputStream = object : OutputStream() {
            private val file = BufferedOutputStream(FileOutputStream(part), 64 * 1024)
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(lock) {
                    if (closed) throw IOException("media-preparation-cancelled")
                    if (bytes + written + len > config.budgetBytes) throw IOException("media-budget-exceeded")
                }
                if (!hasSpace(len)) throw IOException("insufficient-space")
                file.write(b, off, len); written += len
            }
            override fun close() = file.close()
        }
    }

    /** Opens the next segment, waiting while production is more than maxAhead past the receiver's demand. */
    fun begin(startUs: Long, independent: Boolean): OutputStream = synchronized(lock) {
        check(open == null) { "segment-already-open" }
        var waitStart = 0L
        try {
            while (!closed && segments.isNotEmpty() && startUs - demandEndUs > config.maxAheadUs) {
                if (waitStart == 0L) { waitStart = clock(); hold.held = true }
                // Only an independently decodable boundary can be resumed later by seeking the source there.
                if (independent && clock() - waitStart > config.pauseBudgetNs) throw CastSuspended(startUs)
                lock.wait(100)
            }
        } finally { if (waitStart != 0L) hold.held = false }
        if (closed) throw IOException("media-preparation-cancelled")
        val index = segments.size
        val segment = OpenSegment(index, File(directory, "$index.part"), startUs, independent)
        open = segment
        segment.stream
    }

    fun commit(durationUs: Long) {
        val current = synchronized(lock) { checkNotNull(open) }
        current.stream.close()
        synchronized(lock) {
            if (closed) { current.part.delete(); throw IOException("media-preparation-cancelled") }
            val target = File(directory, "${current.index}.ts")
            if (!current.part.renameTo(target)) { current.part.delete(); throw IOException("segment-publish-failed") }
            segments += Segment(current.index, target, current.startUs, durationUs.coerceAtLeast(1), current.written, current.independent)
            bytes += current.written
            open = null
            lock.notifyAll()
        }
    }

    /** Drops an unfinished segment; already published segments stay valid for the receiver. */
    fun discardOpen() {
        val current = synchronized(lock) { open.also { open = null } } ?: return
        runCatching { current.stream.close() }
        current.part.delete()
    }

    fun finish() = synchronized(lock) { check(open == null); finished = true; lock.notifyAll() }
    fun fail(reason: String) = synchronized(lock) { if (!finished) failure = reason; lock.notifyAll() }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(segments.toList(), finished, failure) }
    val publishedEndUs: Long get() = synchronized(lock) { segments.lastOrNull()?.let { it.startUs + it.durationUs } ?: 0L }
    val demandedEndUs: Long get() = synchronized(lock) { demandEndUs }

    /** Records that a receiver fetched [index]; production may then advance up to maxAhead past its end. */
    fun demand(index: Int) = synchronized(lock) {
        segments.getOrNull(index)?.let { demandEndUs = maxOf(demandEndUs, it.startUs + it.durationUs) }
        lastDemandNs = clock()
        lock.notifyAll()
    }
    fun touch() { lastDemandNs = clock() }

    fun segment(index: Int): Segment? = synchronized(lock) { segments.getOrNull(index) }

    /** Waits for a segment to be published; null when the stream ended before it, the store closed or time ran out. */
    fun await(index: Int, timeoutNs: Long, active: () -> Boolean): Segment? = synchronized(lock) {
        val deadline = clock() + timeoutNs
        while (index >= segments.size && !finished && failure == null && !closed && active()) {
            if (clock() >= deadline) return null
            lock.wait(100)
        }
        segments.getOrNull(index)
    }

    override fun close() {
        val current = synchronized(lock) { closed = true; lock.notifyAll(); open.also { open = null } }
        current?.let { runCatching { it.stream.close() }; it.part.delete() }
    }
}

/**
 * MPEG-TS segment output for one production pass. Video is delayed through [CastDtsReorder]; audio is released
 * up to the reconstructed presentation clock, so each segment carries its own interleaved audio. Segments start
 * at key frames once [CastStreamConfig.segmentUs] has elapsed; a source without a key frame by the hard cut is
 * split anyway and that segment is marked dependent.
 */
internal class CastTsSegmenter(private val store: CastSegmentStore, private val config: CastStreamConfig,
    private val shared: Shared) : CastSampleSink {
    /** State that must survive production passes: continuity counters and the selected codec. */
    class Shared { var writer: CastTsWriter? = null; var videoMime: String? = null }
    private class Frame(val data: ByteArray, val key: Boolean)
    private var nal: CastNal? = null
    private var adts: CastAdts? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var tracks = 0
    private lateinit var writer: CastTsWriter
    private val reorder = CastDtsReorder<Frame>()
    private val audio = ArrayDeque<Pair<Long, ByteArray>>()
    private var audioBytes = 0L
    private var out: OutputStream? = null
    private var segmentStartUs = 0L
    private var lastPtsUs = Long.MIN_VALUE
    private var frameUs = 33_333L
    private var clockUs = Long.MIN_VALUE
    private var previousClock = Long.MIN_VALUE
    private var skipped = 0

    override fun addTrack(format: CastTrackFormat): Int {
        val index = tracks++
        if (format.video) {
            check(videoTrack < 0) { "ts-multiple-video" }
            nal = CastNal(format.mime, format.csd); videoTrack = index
            val previous = shared.videoMime
            check(previous == null || previous == format.mime) { "ts-video-codec-changed" }
            shared.videoMime = format.mime
        } else {
            check(audioTrack < 0 && format.mime == "audio/mp4a-latm") { "ts-audio-unsupported" }
            adts = CastAdts(format.csd.firstOrNull() ?: throw IllegalArgumentException("ts-audio-config-unsupported"))
            audioTrack = index
        }
        return index
    }

    override fun start() {
        check(videoTrack >= 0) { "ts-video-missing" }
        writer = shared.writer ?: CastTsWriter(checkNotNull(shared.videoMime), audioTrack >= 0).also { shared.writer = it }
    }

    override fun write(track: Int, data: ByteBuffer, timeUs: Long, key: Boolean) {
        val bytes = ByteArray(data.remaining()).also { data.duplicate().get(it) }
        if (track == videoTrack) reorder.push(timeUs, Frame(bytes, key), ::emitVideo)
        else {
            check(track == audioTrack) { "ts-unknown-track" }
            audio.addLast(timeUs to bytes); audioBytes += bytes.size
            check(audio.size <= 16_384 && audioBytes <= 16L * 1024 * 1024) { "ts-audio-queue-overflow" }
            flushAudio(clockUs)
        }
    }

    private fun emitVideo(frame: Frame, ptsUs: Long, dtsUs: Long) {
        require(dtsUs + OFFSET_US - PCR_LEAD_US >= 0) { "ts-timestamp-range" }
        val current = out
        if (current == null) {
            if (!frame.key) { check(++skipped <= 600) { "ts-key-frame-missing" }; return }
            open(ptsUs, true)
        } else {
            val elapsed = ptsUs - segmentStartUs
            // A 1 s GOP stamped at 29.97/30 fps lands a few microseconds short of 2 s; do not skip that key frame.
            if (frame.key && elapsed + CUT_SLACK_US >= config.segmentUs) cut(ptsUs, true)
            else if (elapsed >= config.hardCutUs) cut(ptsUs, false)
        }
        val unit = checkNotNull(nal).accessUnit(frame.data, frame.data.size, frame.key)
        writer.video(checkNotNull(out), unit, unit.size, ticks(ptsUs), ticks(dtsUs), frame.key, (dtsUs + OFFSET_US - PCR_LEAD_US) * 27)
        if (ptsUs > lastPtsUs) lastPtsUs = ptsUs
        val clock = dtsUs + reorder.delay
        if (previousClock != Long.MIN_VALUE && clock > previousClock) frameUs = (clock - previousClock).coerceIn(1_000, 1_000_000)
        previousClock = clock
        clockUs = clock
        flushAudio(clockUs)
    }

    private fun open(startUs: Long, independent: Boolean) {
        val stream = store.begin(startUs, independent)
        out = stream; segmentStartUs = startUs; lastPtsUs = startUs
        writer.tables(stream)
    }

    private fun cut(nextStartUs: Long, independent: Boolean) {
        flushAudio(nextStartUs)
        store.commit(nextStartUs - segmentStartUs)
        out = null
        open(nextStartUs, independent)
    }

    private fun flushAudio(untilUs: Long) {
        val target = out ?: return
        val framing = adts ?: return
        while (audio.isNotEmpty() && audio.first().first < untilUs) {
            val (time, payload) = audio.removeFirst(); audioBytes -= payload.size
            require(time + OFFSET_US >= 0) { "ts-timestamp-range" }
            val frame = framing.header(payload.size) + payload
            writer.audio(target, frame, frame.size, ticks(time))
        }
    }

    override fun finish() {
        reorder.flush(::emitVideo)
        flushAudio(Long.MAX_VALUE)
        if (out != null) { store.commit(lastPtsUs + frameUs - segmentStartUs); out = null }
        store.finish()
    }

    override fun abort() {
        reorder.clear(); audio.clear(); audioBytes = 0
        out = null
        store.discardOpen()
    }

    companion object {
        /** Like common muxers, start at 1.4 s so DTS (PTS − reorder delay) and the 0.7 s PCR lead stay positive. */
        const val OFFSET_US = 1_400_000L
        const val PCR_LEAD_US = 700_000L
        const val CUT_SLACK_US = 50_000L
        fun ticks(us: Long) = (us + OFFSET_US) * 9 / 100
    }
}

/** One production pass from [startUs] (source time). The producer fixes [timeline] on its first pass. */
internal class CastProduction(val startUs: Long, val timeline: CastTimeline, val sink: CastSampleSink,
    val cancelled: AtomicBoolean, val hold: CastFlowHold)
internal fun interface CastStreamProducer { fun produce(run: CastProduction) }

internal enum class CastStreamOutput(val mime: String) { HLS_TS("application/vnd.apple.mpegurl"), HTTP_TS("video/mp2t") }

/**
 * A published streaming cast: completed TS segments behind a random token, served only to one receiver address.
 * The same segments back an HLS playlist and a continuous HTTP TS response. Production runs on its own thread,
 * waits when it is far ahead of the receiver, releases codecs after a long pause and resumes in place when the
 * receiver asks for media again.
 */
internal class CastStreamSession(parent: File, val receiver: String, private val producer: CastStreamProducer,
    private val config: CastStreamConfig = CastStreamConfig(), styleVersion: Int = 0,
    hasSpace: (Int) -> Boolean = { true }, private val failed: (Throwable) -> Unit = {}) : CastPublication {
    val token: String = LanCrypto.randomId()
    val timeline = CastTimeline(styleVersion)
    private val directory = File(parent, "stream-$token")
    private val cancelled = AtomicBoolean(false)
    private val hold = CastFlowHold()
    private val store: CastSegmentStore
    private val shared = CastTsSegmenter.Shared()
    private val lock = Object() // Wakes a suspended producer when the receiver resumes.
    @Volatile private var suspendedAt: Long? = null
    @Volatile var state = "preparing"; private set
    private var worker: Thread? = null
    val publishedUs get() = store.publishedEndUs

    init {
        require(LanAddress.privateV4(receiver) != null || receiver == "127.0.0.1") { "invalid-receiver" }
        require(directory.mkdirs()) { "stream-directory-unavailable" }
        store = CastSegmentStore(directory, config, hold, hasSpace)
    }

    fun start(startUs: Long) = synchronized(lock) {
        check(worker == null && !cancelled.get())
        worker = Thread({ run(startUs) }, "BIL-Cast-Stream").apply { isDaemon = true; start() }
    }

    private fun run(initial: Long) {
        var start = initial
        try {
            while (true) {
                val sink = CastTsSegmenter(store, config, shared)
                try {
                    producer.produce(CastProduction(start, timeline, sink, cancelled, hold))
                    check(store.snapshot().finished) { "stream-incomplete" }
                    state = "complete"
                    return
                } catch (pause: CastSuspended) {
                    sink.abort()
                    val resume = timeline.sourceUs(pause.resumeOutputUs)
                    suspendedAt = resume; state = "suspended"
                    synchronized(lock) {
                        // Resume once the receiver has consumed half of the buffered lead again.
                        while (!cancelled.get() && store.publishedEndUs - store.demandedEndUs > config.maxAheadUs / 2) lock.wait(250)
                    }
                    check(!cancelled.get()) { "media-preparation-cancelled" }
                    suspendedAt = null; state = "streaming"; start = resume
                } catch (error: Throwable) { sink.abort(); throw error }
            }
        } catch (error: Throwable) {
            state = "failed"
            store.fail(error.message?.takeIf { it.matches(Regex("[a-z0-9-]{1,60}")) } ?: error.javaClass.simpleName)
            if (!cancelled.get()) failed(error)
        }
    }

    /** Returns once [CastStreamConfig.startupUs] of media is published or production completed. */
    fun awaitReady(active: () -> Boolean = { true }) {
        val deadline = System.nanoTime() + config.readyTimeoutNs
        while (true) {
            val snapshot = store.snapshot()
            snapshot.failure?.let { error(it) }
            if (snapshot.finished && snapshot.segments.isNotEmpty()) return
            check(!snapshot.finished) { "empty-stream" }
            if (publishedUs >= config.startupUs) { state = "streaming"; return }
            check(!cancelled.get() && active()) { "media-preparation-cancelled" }
            check(System.nanoTime() < deadline) { "stream-startup-timeout" }
            Thread.sleep(50)
        }
    }

    fun uri(base: String, output: CastStreamOutput) = "$base/media/$token/" + if (output == CastStreamOutput.HLS_TS) "index.m3u8" else "stream.ts"

    fun playlist(): String {
        val snapshot = store.snapshot()
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${config.targetDurationS}\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-START:TIME-OFFSET=0\n")
            snapshot.segments.forEach { segment ->
                append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n%d.ts\n", segment.durationUs / 1_000_000.0, segment.index))
            }
            if (snapshot.finished) append("#EXT-X-ENDLIST\n")
        }
    }

    private fun wake() { store.touch(); synchronized(lock) { lock.notifyAll() } }

    override fun response(request: LanRequest): LanResponse {
        if (cancelled.get()) return LanResponse(404)
        val match = Regex("/media/([A-Za-z0-9_-]{22})/(index\\.m3u8|stream\\.ts|([0-9]{1,6})\\.ts)").matchEntire(request.path)
            ?: return LanResponse(404)
        if (match.groupValues[1] != token) return LanResponse(404)
        if (request.method !in setOf("GET", "HEAD") || request.remote.hostAddress != receiver) return LanResponse(403)
        val dlna = mapOf("transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return when {
            match.groupValues[2] == "index.m3u8" -> {
                wake()
                LanResponse(200, CastStreamOutput.HLS_TS.mime, playlist().toByteArray(Charsets.UTF_8), headers = dlna)
            }
            match.groupValues[2] == "stream.ts" -> continuous(request, dlna)
            else -> segment(request, match.groupValues[3].toInt(), dlna)
        }
    }

    private fun segment(request: LanRequest, index: Int, dlna: Map<String, String>): LanResponse {
        // Never block a request for a future segment; the playlist lists only completed files.
        val segment = store.segment(index) ?: return LanResponse(404)
        store.demand(index); wake()
        val length = segment.bytes
        val range = ByteRange.parse(request.headers["range"], length)
            ?: return LanResponse(416, headers = mapOf("Content-Range" to "bytes */$length"))
        val partial = request.headers["range"] != null
        val headers = linkedMapOf("Accept-Ranges" to "bytes") + dlna +
            (if (partial) mapOf("Content-Range" to "bytes ${range.start}-${range.end}/$length") else emptyMap())
        return LanResponse(if (partial) 206 else 200, CastStreamOutput.HTTP_TS.mime, length = range.length, headers = headers,
            media = true, stream = { out -> copy(segment.file, range.start, range.length, out) })
    }

    private fun continuous(request: LanRequest, dlna: Map<String, String>): LanResponse {
        val range = request.headers["range"]
        // Unknown total length: only a request for the whole stream can be satisfied.
        if (range != null && range.replace(" ", "") != "bytes=0-") return LanResponse(416)
        wake()
        val framing = if (config.chunked && request.version == "HTTP/1.1") LanFraming.CHUNKED else LanFraming.CLOSE
        return LanResponse(200, CastStreamOutput.HTTP_TS.mime, headers = mapOf("Accept-Ranges" to "none") + dlna,
            framing = framing, media = true, stream = { out -> stream(out) })
    }

    private fun stream(out: OutputStream) {
        var index = 0
        while (true) {
            val segment = store.await(index, config.segmentWaitNs) { !cancelled.get() }
            if (segment == null) {
                val snapshot = store.snapshot()
                // A clean end of stream closes normally; failure or timeout aborts the connection.
                if (snapshot.finished && index >= snapshot.segments.size) return
                throw IOException(snapshot.failure ?: "stream-unavailable")
            }
            store.demand(index); wake()
            copy(segment.file, 0, segment.bytes, out)
            out.flush()
            index++
        }
    }

    private fun copy(file: File, start: Long, count: Long, out: OutputStream) {
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = count
            while (remaining > 0) {
                if (cancelled.get()) throw IOException("media-preparation-cancelled")
                val size = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (size < 0) throw IOException("segment-truncated")
                out.write(buffer, 0, size); remaining -= size
            }
        }
    }

    override fun close() {
        cancelled.set(true)
        store.close()
        synchronized(lock) { lock.notifyAll() }
        val thread = synchronized(lock) { worker }
        var interrupted = false
        // Files are deleted only after the producer stopped writing them.
        while (thread != null && thread.isAlive) {
            try { thread.join(100) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
        directory.listFiles()?.forEach(File::delete)
        directory.delete()
    }
}
