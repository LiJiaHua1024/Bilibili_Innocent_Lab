package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/** Complete-MP4 output. MediaMuxer copies each sample synchronously, so the borrowed buffer is not retained. */
internal class CastMuxerSink(private val muxer: MediaMuxer) : CastSampleSink {
    private val info = MediaCodec.BufferInfo()
    private var started = false
    override fun addTrack(format: CastTrackFormat) = muxer.addTrack(format.platform as MediaFormat)
    override fun start() { muxer.start(); started = true }
    override fun write(track: Int, data: ByteBuffer, timeUs: Long, key: Boolean) {
        info.set(data.position(), data.remaining(), timeUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        muxer.writeSampleData(track, data, info)
    }
    override fun finish() { muxer.stop(); started = false }
    override fun abort() { if (started) { started = false; runCatching { muxer.stop() } } }
}

/** The extractor's own end marker (no selected sample track) is EOF; AAC priming may have negative PTS. */
internal class CastExtractorReader(private val extractor: MediaExtractor) : CastSampleReader {
    override fun peekTimeUs(): Long? = if (extractor.sampleTrackIndex < 0) null else extractor.sampleTime
    override val key get() = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
    override fun read(buffer: ByteBuffer) = extractor.readSampleData(buffer, 0)
    override fun advance() { extractor.advance() }
}

internal fun MediaFormat.castTrack(): CastTrackFormat {
    val mime = getString(MediaFormat.KEY_MIME).orEmpty()
    val csd = (0..2).mapNotNull { index ->
        if (!containsKey("csd-$index")) null else getByteBuffer("csd-$index")?.let { value ->
            ByteArray(value.remaining()).also { value.duplicate().get(it) }
        }
    }
    val video = mime.startsWith("video/")
    return CastTrackFormat(mime, csd, if (video) getInteger(MediaFormat.KEY_WIDTH) else 0,
        if (video) getInteger(MediaFormat.KEY_HEIGHT) else 0, this)
}

/** Track choice shared by complete and streaming preparation: largest video, highest-bitrate AAC. */
internal object CastTracks {
    class Track(val extractor: MediaExtractor, val index: Int, val format: MediaFormat)
    fun select(extractors: List<MediaExtractor>): Pair<Track, Track> {
        val videos = ArrayList<Track>(); val audio = ArrayList<Track>()
        extractors.forEach { extractor ->
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                require(!format.containsKey("crypto-key")) { "drm-not-supported" }
                val type = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (type.startsWith("video/")) videos += Track(extractor, index, format)
                if (type == "audio/mp4a-latm") audio += Track(extractor, index, format)
            }
        }
        val video = videos.maxByOrNull { it.format.getInteger(MediaFormat.KEY_WIDTH).toLong() * it.format.getInteger(MediaFormat.KEY_HEIGHT) }
            ?: error("video-track-unavailable")
        val sound = audio.maxByOrNull { if (it.format.containsKey(MediaFormat.KEY_BIT_RATE)) it.format.getInteger(MediaFormat.KEY_BIT_RATE) else 0 }
            ?: error("audio-track-unavailable")
        return video to sound
    }
}
