package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Opt-in local benchmarks; only castRemote permits CDN requests. Never controls a TV or reads host credentials. */
@RunWith(AndroidJUnit4::class)
class CastVideoEncoderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val directory = File(instrumentation.targetContext.filesDir, "cast-benchmark")

    @Test fun explicitlyRequestedColdPreparationUsesTheProductionPipeline() {
        assumeTrue(arguments.getString("castRemote") == "true")
        val json = JSONObject(File(directory, "source.json").readText())
        val values = json.getJSONArray("urls")
        val source = CastSource((0 until values.length()).map(values::getString), json.getString("bvid"), json.getLong("cid"),
            headers = mapOf("User-Agent" to json.getString("userAgent"), "Referer" to "https://www.bilibili.com/"))
        val comments = CastDanmakuSource.parse(File(directory, "comments.xml").readText())
        val begin = System.nanoTime()
        CastMedia(File(directory, "cold-preparation")).use { media ->
            val output = media.prepare(source, CastDanmakuStyle(), comments) { message ->
                instrumentation.sendStatus(2, Bundle().apply { putString("castProgress", message) })
            }
            val elapsedMs = (System.nanoTime() - begin) / 1_000_000
            val actual = trackStats(output)
            val expected = trackStats(File(directory, "clean480.mp4"))
            val report = "coldPrepareMs=$elapsedMs stats=$actual"
            File(directory, "cold-preparation.txt").writeText(report)
            instrumentation.sendStatus(2, Bundle().apply { putString("castResult", report) })
            assertEquals(expected.videoSamples, actual.videoSamples)
            assertEquals(expected.audioSamples, actual.audioSamples)
            assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
            assertEquals(expected.videoLastUs.toDouble(), actual.videoLastUs.toDouble(), 100.0)
            assertEquals(expected.audioLastUs.toDouble(), actual.audioLastUs.toDouble(), 100.0)
        }
    }

    @Test fun localMediaKeepsFramesAndAudioWhileCompositing() {
        val inputName = arguments.getString("castInput")
        assumeTrue("provide castInput inside files/cast-benchmark", inputName != null)
        require(inputName!!.matches(Regex("[A-Za-z0-9_.-]+\\.mp4")))
        val input = File(directory, inputName)
        require(input.isFile)
        val variant = arguments.getString("castVariant") ?: "current"
        require(variant in setOf("legacy", "current", "unhinted", "hinted"))
        val output = File(directory, "$variant-${input.name}")
        val commentsFile = File(directory, "comments.xml")
        val comments = if (commentsFile.isFile) CastDanmakuSource.parse(commentsFile.readText()) else
            (0 until 9520).map { index -> CastDanmaku(index * 100L, 1, -1, "清晰度测试 中文 English 123 $index") }
        val video = MediaExtractor()
        val audio = MediaExtractor()
        var muxer: MediaMuxer? = null
        val metrics = CastPreparationMetrics()
        try {
            video.setDataSource(input.path); audio.setDataSource(input.path)
            val videoIndex = (0 until video.trackCount).first { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audioIndex = (0 until audio.trackCount).first { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            video.selectTrack(videoIndex); audio.selectTrack(audioIndex)
            val format = video.getTrackFormat(videoIndex)
            val writer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val audioTrack = writer.addTrack(audio.getTrackFormat(audioIndex))
            val style = if (arguments.getString("castLegacyTextSize") == "true" && variant != "legacy") {
                val width = format.getInteger(MediaFormat.KEY_WIDTH); val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                CastDanmakuStyle(scale = height / 720f / maxOf(width / 1280f, height / 720f))
            } else CastDanmakuStyle()
            val begin = System.nanoTime()
            val progress: (String) -> Unit = { message ->
                instrumentation.sendStatus(2, Bundle().apply { putString("castProgress", "$variant $message") })
            }
            if (variant == "legacy") CastLegacyBenchmarkEncoder.transcode(video, format, writer, audioTrack, audio,
                style, comments, AtomicBoolean(false), progress)
            else CastVideoEncoder.transcode(video, format, CastMuxerSink(writer), audioTrack, CastExtractorReader(audio),
                style, comments, AtomicBoolean(false), metrics, useRateHint = variant == "hinted", progress = progress)
            val elapsedMs = (System.nanoTime() - begin) / 1_000_000
            writer.release(); muxer = null
            val expected = trackStats(input)
            val actual = trackStats(output)
            val report = "variant=$variant input=${input.name} scale=${style.scale} elapsedMs=$elapsedMs bytes=${output.length()} stats=$actual ${metrics.summary(true)}"
            File(directory, "$variant-${input.nameWithoutExtension}.txt").writeText(report)
            instrumentation.sendStatus(2, Bundle().apply { putString("castResult", report) })
            assertEquals("video frame count", expected.videoSamples, actual.videoSamples)
            assertEquals("audio sample count", expected.audioSamples, actual.audioSamples)
            // Source/container time scales can round by microseconds (observed 1 us on the full AVC fixture).
            assertEquals("last video timestamp", expected.videoLastUs.toDouble(), actual.videoLastUs.toDouble(), 100.0)
            assertEquals("last audio timestamp", expected.audioLastUs.toDouble(), actual.audioLastUs.toDouble(), 100.0)
            assertEquals("width", expected.width, actual.width)
            assertEquals("height", expected.height, actual.height)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.path)
                for (timeUs in listOf(2_000_000L, 90_000_000L)) {
                    if (timeUs > actual.videoLastUs) continue
                    val frame = checkNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                    try { File(directory, "$variant-${input.nameWithoutExtension}-$timeUs.png").outputStream().use {
                        assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { frame.recycle() }
                }
            } finally { retriever.release() }
        } finally {
            video.release(); audio.release(); muxer?.release()
        }
    }

    @Test fun cancellationJoinsOutputAndFrameCallbacksBeforeReturning() {
        assumeTrue(arguments.getString("castCancel") == "true")
        val input = File(directory, "short720.mp4")
        require(input.isFile)
        val output = File(directory, "cancelled.mp4")
        val video = MediaExtractor(); val audio = MediaExtractor()
        var muxer: MediaMuxer? = null
        val cancelled = AtomicBoolean(false)
        try {
            video.setDataSource(input.path); audio.setDataSource(input.path)
            val vi = (0 until video.trackCount).first { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val ai = (0 until audio.trackCount).first { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            video.selectTrack(vi); audio.selectTrack(ai)
            val writer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val track = writer.addTrack(audio.getTrackFormat(ai))
            val failure = runCatching {
                CastVideoEncoder.transcode(video, video.getTrackFormat(vi), CastMuxerSink(writer), track, CastExtractorReader(audio), CastDanmakuStyle(),
                    listOf(CastDanmaku(0, 5, -1, "取消后的资源回收")), cancelled, CastPreparationMetrics()) { cancelled.set(true) }
            }.exceptionOrNull()
            assertTrue(cancelled.get()); assertNotNull(failure)
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name in setOf("BIL-Cast-Input", "BIL-Cast-Output", "BIL-Cast-Frames") })
        } finally { video.release(); audio.release(); muxer?.release(); output.delete() }
    }

    private data class Stats(val width: Int, val height: Int, val videoSamples: Long, val audioSamples: Long,
        val videoLastUs: Long, val audioLastUs: Long)

    private fun trackStats(file: File): Stats {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            var width = 0; var height = 0; var videos = 0L; var audios = 0L; var videoLast = -1L; var audioLast = -1L
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val isVideo = format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                if (isVideo) { width = format.getInteger(MediaFormat.KEY_WIDTH); height = format.getInteger(MediaFormat.KEY_HEIGHT) }
                extractor.selectTrack(index); extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var count = 0L; var last = -1L
                while (extractor.sampleTime >= 0) {
                    val time = extractor.sampleTime
                    // AVC input may contain B-frame reordering; compare total samples and final timeline extent.
                    last = maxOf(last, time); count++; extractor.advance()
                }
                if (isVideo) { videos = count; videoLast = last } else { audios = count; audioLast = last }
                extractor.unselectTrack(index)
            }
            return Stats(width, height, videos, audios, videoLast, audioLast)
        } finally { extractor.release() }
    }
}
