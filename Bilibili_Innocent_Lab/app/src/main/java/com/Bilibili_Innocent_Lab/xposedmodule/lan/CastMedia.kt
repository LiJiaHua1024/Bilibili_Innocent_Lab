package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Signed URLs come from the active host player; they never enter discovery or continuity packets. */
internal data class CastSource(val urls: List<String>, val bvid: String, val cid: Long,
    val backups: Map<String, List<String>> = emptyMap(), val headers: Map<String, String> = emptyMap()) {
    init {
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}")) && cid > 0)
        require(urls.isNotEmpty() && urls.size <= 16); urls.forEach { validateMediaUrl(it, allowHttp = true) }
        require(backups.keys.all { it in urls } && backups.values.all { it.size <= 7 })
        backups.values.flatten().forEach { validateMediaUrl(it, allowHttp = true) }
        require(headers.keys.all { it in setOf("User-Agent", "Referer") } && headers.values.all {
            it.length in 1..4096 && it.none { c -> c == '\r' || c == '\n' || c == '\u0000' }
        })
    }
}
internal fun validateMediaUrl(value: String, allowHttp: Boolean = false) {
    val uri = URI(value)
    val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
    require(value.length <= 16_384 && uri.rawFragment == null && uri.rawUserInfo == null &&
        (uri.scheme == "https" && uri.port in setOf(-1, 443) || allowHttp && uri.scheme == "http" && uri.port in setOf(-1, 80)) &&
        listOf("bilivideo.com", "bilivideo.cn", "bilibili.com").any { host == it || host.endsWith(".$it") }) { "untrusted-media-origin" }
}

/** A media URL the receiver may fetch; [response] enforces the random token and the receiver address. */
internal interface CastPublication : AutoCloseable { fun response(request: LanRequest): LanResponse }

/** Lossless track remux: no resolution cap, no TV account API, no entitlement or DRM mutation. */
internal class CastMedia(private val directory: File,
    private val open: (String) -> java.net.HttpURLConnection = { java.net.URL(it).openConnection() as java.net.HttpURLConnection }) : CastPublication {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var ready: File? = null
    @Volatile private var token: String? = null
    @Volatile private var receiver: String? = null
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.HttpURLConnection>()
    private val publication = Any()
    fun prepare(source: CastSource, overlay: CastDanmakuStyle?, comments: List<CastDanmaku>, progress: (String) -> Unit): File {
        val metrics = CastPreparationMetrics()
        var success = false
        check(!cancelled.get())
        directory.mkdirs()
        // Use actual free bytes conservatively; do not evict the host's other caches to satisfy a cast.
        require(directory.usableSpace > 256L * 1024 * 1024) { "insufficient-space" }
        val output = File(directory, "${LanCrypto.randomId()}.mp4")
        val inputs = ArrayList<File>()
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val budget = minOf(2L * 1024 * 1024 * 1024, directory.usableSpace / 3)
            val sharedBudget = CastDownloadBudget(budget)
            val space = CastWriteSpace({ directory.usableSpace })
            val urls = source.urls.distinct()
            urls.indices.forEach { index -> inputs += File(directory, "${output.nameWithoutExtension}-$index.track") }
            val downloadStart = System.nanoTime()
            try {
                metrics.downloadedBytes = CastParallelTracks.download(urls.size, cancelled, {
                    connections.forEach { runCatching { it.disconnect() } }
                }, { complete -> progress("正在并行读取已授权媒体轨道 $complete/${urls.size}") }) { index ->
                    val url = urls[index]
                    CastMediaFetch(open, cancelled, connections, { code, host ->
                        android.util.Log.w("BIL-LAN", "media candidate rejected http=$code host=$host")
                    }).download(listOf(url) + source.backups[url].orEmpty(),
                        source.headers, inputs[index], budget, sharedBudget, space::allows)
                }
            } finally { metrics.downloadNs = System.nanoTime() - downloadStart }
            inputs.forEach { input ->
                check(!cancelled.get())
                val extractor = MediaExtractor().also(extractors::add)
                extractor.setDataSource(input.absolutePath)
            }
            val (video, sound) = CastTracks.select(extractors.toList())
            // One extractor has only one selected track at a time. A second instance preserves progressive A/V.
            val audioExtractor = MediaExtractor().also(extractors::add)
            audioExtractor.setDataSource(inputs[extractors.indexOf(sound.extractor)].absolutePath)
            audioExtractor.selectTrack(sound.index)
            video.extractor.selectTrack(video.index)
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val audioTrack = writer.addTrack(sound.format)
            if (overlay != null && overlay.enabled && comments.isNotEmpty()) {
                progress("正在合成弹幕视频；保持此页面开启")
                CastVideoEncoder.transcode(video.extractor, video.format, CastMuxerSink(writer), audioTrack,
                    CastExtractorReader(audioExtractor), overlay, comments, cancelled, metrics) {
                        require(directory.usableSpace > 64L * 1024 * 1024 && output.length() <= 2L * 1024 * 1024 * 1024) { "media-budget-exceeded" }
                        progress(it)
                    }
                // Encoder owns start/stop because its output format arrives asynchronously.
            } else {
                val videoTrack = writer.addTrack(video.format)
                writer.start(); started = true
                progress(if (overlay?.enabled == true) "暂无可用弹幕，正在合并原画音视频" else "正在合并原画音视频；完成后主动推送到电视")
                val videoStart = System.nanoTime()
                copy(video.extractor, writer, videoTrack)
                metrics.videoNs = System.nanoTime() - videoStart
                val audioStart = System.nanoTime()
                copy(audioExtractor, writer, audioTrack)
                metrics.audioNs = System.nanoTime() - audioStart
                val finalizeStart = System.nanoTime()
                writer.stop(); started = false
                metrics.finalizeNs = System.nanoTime() - finalizeStart
            }
            synchronized(publication) { check(!cancelled.get()); ready = output }
            success = true
            return output
        } catch (failure: Exception) { output.delete(); throw failure }
        finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }; extractors.forEach { runCatching { it.release() } }
            inputs.forEach(File::delete)
            android.util.Log.i("BIL-LAN", metrics.summary(success))
        }
    }
    private fun copy(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        val space = CastWriteSpace({ directory.usableSpace })
        while (!cancelled.get()) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            require(size <= buffer.capacity())
            require(space.allows(size)) { "insufficient-space" }
            info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
        check(!cancelled.get())
    }
    fun publish(receiverIp: String, base: String): String = synchronized(publication) {
        check(!cancelled.get())
        check(ready?.isFile == true)
        require(LanAddress.privateV4(receiverIp) != null)
        receiver = receiverIp; token = LanCrypto.randomId()
        "$base/media/${token}.mp4"
    }
    override fun response(request: LanRequest): LanResponse {
        val current = ready ?: return LanResponse(404)
        val secret = token ?: return LanResponse(404)
        if (request.method !in setOf("GET", "HEAD") || request.path != "/media/$secret.mp4" ||
            request.remote.hostAddress != receiver) return LanResponse(403)
        val length = current.length()
        val range = ByteRange.parse(request.headers["range"], length)
            ?: return LanResponse(416, headers = mapOf("Content-Range" to "bytes */$length"))
        val partial = request.headers["range"] != null
        val headers = linkedMapOf("Accept-Ranges" to "bytes", "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        if (partial) headers["Content-Range"] = "bytes ${range.start}-${range.end}/$length"
        return LanResponse(if (partial) 206 else 200, "video/mp4", length = range.length, headers = headers, media = true, stream = { out ->
            RandomAccessFile(current, "r").use { input ->
                input.seek(range.start)
                val buffer = ByteArray(64 * 1024)
                var remaining = range.length
                while (remaining > 0 && !cancelled.get()) {
                    val size = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (size < 0) break
                    out.write(buffer, 0, size); remaining -= size
                }
            }
        })
    }
    override fun close() {
        synchronized(publication) {
            cancelled.set(true); token = null; receiver = null
            ready?.delete(); ready = null
        }
        connections.forEach { runCatching { it.disconnect() } }
    }
}
