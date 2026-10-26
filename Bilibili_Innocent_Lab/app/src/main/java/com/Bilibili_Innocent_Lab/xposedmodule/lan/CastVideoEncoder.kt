package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Surface decode → GLES composition → hardware AVC encode; audio is copied without recompression. */
internal object CastVideoEncoder {
    /**
     * Frames before a positive [startUs] are decoded but not rendered (the caller seeks to the previous sync
     * sample). Danmaku use source time; encoded PTS is source time − [offsetUs]. [audio] must already yield
     * output time. While [hold] is set the sink is waiting for the receiver on purpose, so stall checks pause.
     */
    fun transcode(video: MediaExtractor, format: MediaFormat, sink: CastSampleSink, audioTrack: Int,
        audio: CastSampleReader, style: CastDanmakuStyle, comments: List<CastDanmaku>, cancelled: AtomicBoolean,
        metrics: CastPreparationMetrics, startUs: Long = 0, offsetUs: Long = 0, hold: CastFlowHold = CastFlowHold(),
        useRateHint: Boolean = false, progress: (String) -> Unit) {
        val videoStart = System.nanoTime()
        val width = format.getInteger(MediaFormat.KEY_WIDTH); val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        require(width in 16..7680 && height in 16..4320 && width % 2 == 0 && height % 2 == 0)
        require(!format.containsKey(MediaFormat.KEY_ROTATION) || format.getInteger(MediaFormat.KEY_ROTATION) == 0) { "rotated-overlay-not-supported" }
        // The 8-bit overlay path has no HDR tone mapper; refuse it instead of silently washing out colors.
        require(!format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) && (!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ||
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG))) { "hdr-overlay-not-supported" }
        var encoder = MediaCodec.createEncoderByType("video/avc")
        metrics.encoder = encoder.name
        var decoder: MediaCodec? = null
        var render: CastSurface? = null
        var drain: CastEncoderDrain? = null
        var feed: CastDecoderFeed? = null
        var encoderStarted = false
        var decoderStarted = false
        try {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("missing-video-mime")
            decoder = MediaCodec.createDecoderByType(mime)
            val sourceFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120) else 30
            val sourceIndex = video.sampleTrackIndex
            require(sourceIndex >= 0) { "empty-video-track" }
            val rate = if (useRateHint) CastCodecRatePolicy.requested(sourceFps,
                rateLimit(checkNotNull(decoder), mime, width, height), rateLimit(encoder, "video/avc", width, height)) else 0f
            fun outputFormat() = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * 6).coerceIn(2_000_000, 60_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Offline transcoding has no wall-clock playback deadline; retain the source frame rate/PTS.
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER).forEach { key ->
                    if (format.containsKey(key)) setInteger(key, format.getInteger(key))
                }
            }
            require(encoder.codecInfo.getCapabilitiesForType("video/avc").isFormatSupported(outputFormat())) { "encoder-resolution-not-supported" }
            encoder = configure(encoder, null, MediaCodec.CONFIGURE_FLAG_ENCODE, rate, ::outputFormat) { metrics.encoderRateHint = it }
            metrics.encoder = encoder.name
            metrics.width = width; metrics.height = height; metrics.sourceFps = sourceFps
            val surface = CastSurface(encoder.createInputSurface(), width, height, comments, style, metrics, cancelled).also { render = it }
            encoder.start(); encoderStarted = true
            val codec = configure(checkNotNull(decoder), surface.decoderSurface, 0, rate, {
                video.getTrackFormat(sourceIndex).apply { setInteger(MediaFormat.KEY_PRIORITY, 1) }
            }) { metrics.decoderRateHint = it }.also { decoder = it }
            metrics.decoder = codec.name
            codec.start(); decoderStarted = true
            val output = CastEncoderDrain(encoder, sink, audioTrack, audio, cancelled, metrics, progress).also { drain = it }
            val input = CastDecoderFeed(codec, video, cancelled, metrics, hold).also { feed = it }
            val decoded = MediaCodec.BufferInfo()
            var decodeDone = false
            var lastOutput = System.nanoTime()
            while (!decodeDone) {
                output.checkHealth(); input.checkHealth()
                val begin = System.nanoTime()
                val slot = codec.dequeueOutputBuffer(decoded, 10_000)
                metrics.codecIdleNs += System.nanoTime() - begin
                if (slot >= 0) {
                    val frame = decoded.size > 0
                    // A zero start renders every frame, exactly as the complete-MP4 path always did.
                    val render = frame && (startUs <= 0 || decoded.presentationTimeUs >= startUs)
                    codec.releaseOutputBuffer(slot, render)
                    if (render) {
                        try { surface.draw(decoded.presentationTimeUs, decoded.presentationTimeUs - offsetUs) }
                        catch (error: Throwable) { output.checkHealth(); input.checkHealth(); throw error }
                    }
                    if (frame) lastOutput = System.nanoTime()
                    if (decoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decodeDone = true; encoder.signalEndOfInputStream()
                    }
                } else if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) lastOutput = System.nanoTime()
                if (hold.held) lastOutput = System.nanoTime()
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "decoder-stalled" }
            }
            output.awaitCompletion()
            metrics.videoNs = System.nanoTime() - videoStart - metrics.finalizeNs
        } finally {
            feed?.close()
            drain?.close()
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }; runCatching { render?.close() }
        }
    }

    private fun rateLimit(codec: MediaCodec, mime: String, width: Int, height: Int): Double? = runCatching {
        codec.codecInfo.getCapabilitiesForType(mime).videoCapabilities?.getSupportedFrameRatesFor(width, height)?.upper
    }.getOrNull()

    /** Some vendors reject optional operating-rate hints. Retry a fresh codec with the original format. */
    private fun configure(initial: MediaCodec, surface: Surface?, flags: Int, rate: Float,
        format: () -> MediaFormat, selected: (Float) -> Unit): MediaCodec {
        val name = initial.name
        try {
            initial.configure(format().apply { if (rate > 0f) setFloat(MediaFormat.KEY_OPERATING_RATE, rate) }, surface, null, flags)
            selected(rate)
            return initial
        } catch (failure: Exception) {
            if (rate <= 0f) throw failure
            initial.release()
            val retry = MediaCodec.createByCodecName(name)
            try {
                retry.configure(format(), surface, null, flags)
                selected(0f)
                return retry
            } catch (retryFailure: Throwable) {
                runCatching { retry.release() }
                retryFailure.addSuppressed(failure)
                throw retryFailure
            }
        }
    }
}

/** Owned entirely by the preparation worker. The frame callback only signals a condition. */
private class CastSurface(private val input: Surface, private val width: Int, private val height: Int,
    comments: List<CastDanmaku>, style: CastDanmakuStyle, private val metrics: CastPreparationMetrics,
    private val cancelled: AtomicBoolean) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var videoProgram: CastGlProgram? = null
    private var overlay: CastDanmakuRenderer? = null
    private var arrivalThread: HandlerThread? = null
    private var source: SurfaceTexture? = null
    private var output: Surface? = null
    val decoderSurface: Surface get() = checkNotNull(output)
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val coords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private val frame = Object() // Java monitor methods are required for the SurfaceTexture callback rendezvous.
    private var available = false
    private var closed = false
    init {
        try {
        require(display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2); require(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
        require(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        require(context != EGL14.EGL_NO_CONTEXT)
        window = EGL14.eglCreateWindowSurface(display, configs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
        require(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context))
        val names = IntArray(1); GLES20.glGenTextures(1, names, 0); texture = names[0]
        configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        videoProgram = CastGlProgram(true)
        overlay = CastDanmakuRenderer(comments, width, height, style, metrics)
        // Separate from both the blocked GL worker and the host UI thread, as in FrameGlCapture.
        val callbacks = HandlerThread("BIL-Cast-Frames").also { arrivalThread = it; it.start() }
        source = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ synchronized(frame) { available = true; frame.notifyAll() } }, Handler(callbacks.looper))
        }
        output = Surface(checkNotNull(source))
        } catch (failure: Throwable) { close(); throw failure }
    }
    private fun configureTexture(target: Int, name: Int) {
        GLES20.glBindTexture(target, name)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    fun draw(sourceUs: Long, presentationUs: Long) {
        val waitStart = System.nanoTime()
        synchronized(frame) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!available) {
                check(!cancelled.get())
                require(System.nanoTime() < deadline) { "decoder-frame-timeout" }
                frame.wait(100)
            }
            available = false
        }
        metrics.frameWaitNs += System.nanoTime() - waitStart
        val composeStart = System.nanoTime()
        val source = checkNotNull(source)
        source.updateTexImage(); source.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, width, height); GLES20.glDisable(GLES20.GL_BLEND)
        val shader = checkNotNull(videoProgram)
        shader.bind(texture, matrix)
        vertices.position(0); coords.position(0)
        GLES20.glEnableVertexAttribArray(shader.position)
        GLES20.glVertexAttribPointer(shader.position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(shader.coordinate)
        GLES20.glVertexAttribPointer(shader.coordinate, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkNotNull(overlay).draw(sourceUs / 1000)
        require(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "composition-failed" }
        EGLExt.eglPresentationTimeANDROID(display, window, presentationUs * 1000)
        metrics.composeNs += System.nanoTime() - composeStart
        val swapStart = System.nanoTime()
        require(EGL14.eglSwapBuffers(display, window)) { "encoder-surface-failed" }
        metrics.swapNs += System.nanoTime() - swapStart
        metrics.frames++
    }
    override fun close() {
        if (closed) return
        closed = true
        source?.setOnFrameAvailableListener(null)
        output?.release(); source?.release()
        arrivalThread?.quitSafely()
        try { arrivalThread?.join(1000) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        arrivalThread = null
        if (context != EGL14.EGL_NO_CONTEXT) {
            overlay?.close(); videoProgram?.close()
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
        }
        EGL14.eglReleaseThread()
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        input.release()
    }
}
