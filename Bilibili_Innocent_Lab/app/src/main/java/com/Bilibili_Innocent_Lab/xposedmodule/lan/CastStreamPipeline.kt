package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.media.MediaDataSource
import android.media.MediaExtractor
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/** Requested delivery. Complete MP4 is the verified default; the stream outputs still await receiver acceptance. */
internal enum class CastDelivery(val stream: CastStreamOutput?) {
    COMPLETE_MP4(null), HLS_TS(CastStreamOutput.HLS_TS), HTTP_TS(CastStreamOutput.HTTP_TS)
}

/** A published streaming cast. Closing stops production first, then deletes segments and track caches. */
internal class CastStreamCast(val session: CastStreamSession, private val pipeline: CastStreamPipeline,
    val output: CastStreamOutput) : CastPublication {
    override fun response(request: LanRequest) = session.response(request)
    override fun close() { try { session.close() } finally { pipeline.close() } }
}

/** MediaExtractor input over [CastRangeCache]; the cache is owned and closed by [CastStreamPipeline]. */
internal class CastRangeDataSource(private val cache: CastRangeCache) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = cache.readAt(position, buffer, offset, size)
    override fun getSize(): Long = cache.length
    override fun close() = Unit
}

/**
 * Streaming producer for one cast: on-demand track caches shared by every production pass, the same track
 * choice as complete preparation, then either GPU danmaku composition or a passthrough remux into the sink.
 * Each pass opens fresh extractors and codecs and releases them before returning.
 */
internal class CastStreamPipeline(private val directory: File, private val source: CastSource,
    private val overlay: CastDanmakuStyle?, private val comments: List<CastDanmaku>,
    private val open: (String) -> HttpURLConnection, private val progress: (String) -> Unit) : CastStreamProducer, AutoCloseable {
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val lock = Any()
    private var caches: List<CastRangeCache>? = null
    private var closed = false
    val composing get() = overlay != null && overlay.enabled && comments.isNotEmpty()

    private fun caches(cancelled: AtomicBoolean): List<CastRangeCache> = synchronized(lock) {
        check(!closed) { "media-preparation-cancelled" }
        caches?.let { return it }
        directory.mkdirs()
        // Same conservative space rule as complete preparation; the host's other caches are never evicted.
        require(directory.usableSpace > 256L * 1024 * 1024) { "insufficient-space" }
        val budget = minOf(2L * 1024 * 1024 * 1024, directory.usableSpace / 3)
        val shared = CastDownloadBudget(budget)
        val space = CastWriteSpace({ directory.usableSpace })
        val opened = ArrayList<CastRangeCache>()
        try {
            source.urls.distinct().forEachIndexed { index, url ->
                progress("正在按需读取已授权媒体轨道 ${index + 1}/${source.urls.distinct().size}")
                opened += CastRangeCache(File(directory, "${LanCrypto.randomId()}-$index.range"), listOf(url) + source.backups[url].orEmpty(),
                    source.headers, open, cancelled, connections, budget, shared, space::allows, rejected = { code, host ->
                        android.util.Log.w("BIL-LAN", "media candidate rejected http=$code host=$host")
                    }).also { it.start() }
            }
        } catch (failure: Throwable) { opened.forEach { runCatching { it.close() } }; throw failure }
        opened.also { caches = it }
    }

    override fun produce(run: CastProduction) {
        val sources = caches(run.cancelled)
        val metrics = CastPreparationMetrics()
        val extractors = ArrayList<MediaExtractor>()
        var success = false
        try {
            sources.forEach { cache -> MediaExtractor().also(extractors::add).setDataSource(CastRangeDataSource(cache)) }
            val (video, sound) = CastTracks.select(extractors.toList())
            val audioExtractor = if (sound.extractor !== video.extractor) sound.extractor else {
                // One extractor selects one track at a time; a muxed source needs a second reader of the same cache.
                val cache = sources[extractors.indexOf(sound.extractor)]
                MediaExtractor().also { extractors += it; it.setDataSource(CastRangeDataSource(cache)) }
            }
            audioExtractor.selectTrack(sound.index)
            video.extractor.selectTrack(video.index)
            val start = run.startUs.coerceAtLeast(0)
            if (start > 0) video.extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sink = run.sink
            if (composing) {
                val offset = run.timeline.fix(start)
                if (start > 0) audioExtractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val audioTrack = sink.addTrack(sound.format.castTrack())
                progress("正在边合成边准备弹幕视频")
                CastVideoEncoder.transcode(video.extractor, video.format, sink, audioTrack,
                    CastShiftedReader(CastExtractorReader(audioExtractor), start, offset), checkNotNull(overlay), comments,
                    run.cancelled, metrics, start, offset, run.hold, progress = progress)
            } else {
                // Passthrough segments must begin on a source sync sample, so the session clock starts there.
                val keyStart = if (start > 0) video.extractor.sampleTime.also { require(it >= 0) { "seek-unavailable" } } else 0L
                val offset = run.timeline.fix(keyStart)
                if (keyStart > 0) audioExtractor.seekTo(keyStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val videoTrack = sink.addTrack(video.format.castTrack())
                val audioTrack = sink.addTrack(sound.format.castTrack())
                sink.start()
                progress("正在边读取边准备原画分片")
                val begin = System.nanoTime()
                CastInterleave.copy(CastShiftedReader(CastExtractorReader(video.extractor), keyStart, offset), videoTrack,
                    CastShiftedReader(CastExtractorReader(audioExtractor), keyStart, offset), audioTrack, sink, run.cancelled)
                metrics.videoNs = System.nanoTime() - begin
                sink.finish()
            }
            success = true
        } finally {
            extractors.forEach { runCatching { it.release() } }
            metrics.downloadedBytes = sources.sumOf { it.fetchedBytes }
            android.util.Log.i("BIL-LAN", "stream-pass start=${run.startUs / 1000}ms " + metrics.summary(success))
        }
    }

    /** Call only after the owning session stopped production; deletes the sparse track files. */
    override fun close() {
        val owned = synchronized(lock) { closed = true; caches.also { caches = null } }
        connections.forEach { runCatching { it.disconnect() } }
        owned?.forEach { runCatching { it.close() } }
    }
}
