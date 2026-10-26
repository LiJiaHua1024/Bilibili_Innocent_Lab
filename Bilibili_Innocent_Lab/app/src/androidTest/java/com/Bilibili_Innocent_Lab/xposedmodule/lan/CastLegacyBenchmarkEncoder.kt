package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Frozen 2b9e02fb CPU-overlay pipeline for opt-in device A/B measurements; never shipped in the app. */
internal object CastLegacyBenchmarkEncoder {
    fun transcode(video: MediaExtractor, format: MediaFormat, muxer: MediaMuxer, audioTrack: Int,
        audio: MediaExtractor, style: CastDanmakuStyle, comments: List<CastDanmaku>, cancelled: AtomicBoolean,
        progress: (String) -> Unit) {
        val width = format.getInteger(MediaFormat.KEY_WIDTH); val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        require(width in 16..7680 && height in 16..4320 && width % 2 == 0 && height % 2 == 0)
        require(!format.containsKey(MediaFormat.KEY_ROTATION) || format.getInteger(MediaFormat.KEY_ROTATION) == 0) { "rotated-overlay-not-supported" }
        // The 8-bit overlay path has no HDR tone mapper; refuse it instead of silently washing out colors.
        require(!format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) && (!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ||
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG))) { "hdr-overlay-not-supported" }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        var decoder: MediaCodec? = null
        var render: CastLegacyBenchmarkSurface? = null
        var started = false
        var encoderStarted = false
        var decoderStarted = false
        try {
            val outputFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * 6).coerceIn(2_000_000, 60_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120) else 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER).forEach { key ->
                    if (format.containsKey(key)) setInteger(key, format.getInteger(key))
                }
            }
            require(encoder.codecInfo.getCapabilitiesForType("video/avc").isFormatSupported(outputFormat)) { "encoder-resolution-not-supported" }
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = CastLegacyBenchmarkSurface(encoder.createInputSurface(), width, height, CastLegacyBenchmarkPainter(comments, width, height, style)).also { render = it }
            encoder.start(); encoderStarted = true
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: error("missing-video-mime")).also { decoder = it }
            codec.configure(format, surface.decoderSurface, null, 0); codec.start(); decoderStarted = true
            val decoded = MediaCodec.BufferInfo(); val encoded = MediaCodec.BufferInfo()
            var inputDone = false; var decodeDone = false; var encodeDone = false; var videoTrack = -1
            var lastProgress = -1L
            var lastOutput = System.nanoTime()
            while (!encodeDone) {
                check(!cancelled.get())
                if (!inputDone) {
                    val slot = codec.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(slot)).apply { clear() }
                        val count = video.readSampleData(buffer, 0)
                        if (count < 0) { codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            require(count <= buffer.capacity())
                            codec.queueInputBuffer(slot, 0, count, video.sampleTime, 0); video.advance()
                        }
                    }
                }
                if (!decodeDone) {
                    val slot = codec.dequeueOutputBuffer(decoded, 10_000)
                    if (slot >= 0) {
                        val frame = decoded.size > 0
                        codec.releaseOutputBuffer(slot, frame)
                        if (frame) { surface.draw(decoded.presentationTimeUs); lastOutput = System.nanoTime() }
                        if (decoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decodeDone = true; encoder.signalEndOfInputStream()
                        }
                    }
                }
                while (true) {
                    val slot = encoder.dequeueOutputBuffer(encoded, if (decodeDone) 10_000 else 0)
                    if (slot == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!started)
                        videoTrack = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true
                    } else if (slot >= 0) {
                        if (encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encoded.size > 0) {
                            check(started)
                            val buffer = checkNotNull(encoder.getOutputBuffer(slot))
                            buffer.position(encoded.offset); buffer.limit(encoded.offset + encoded.size)
                            muxer.writeSampleData(videoTrack, buffer, encoded)
                            val seconds = encoded.presentationTimeUs / 1_000_000
                            if (seconds / 5 != lastProgress) { lastProgress = seconds / 5; progress("已合成 ${seconds} 秒弹幕视频") }
                        }
                        encodeDone = encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(slot, false); lastOutput = System.nanoTime()
                        if (encodeDone) break
                    }
                }
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "encoder-stalled" }
            }
            val audioBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            while (!cancelled.get()) {
                audioBuffer.clear()
                val count = audio.readSampleData(audioBuffer, 0)
                if (count < 0) break
                require(count <= audioBuffer.capacity())
                encoded.set(0, count, audio.sampleTime, 0); muxer.writeSampleData(audioTrack, audioBuffer, encoded); audio.advance()
            }
            check(!cancelled.get())
            muxer.stop(); started = false
        } finally {
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }; runCatching { render?.close() }
            if (started) runCatching { muxer.stop() }
        }
    }
}

/** Owned entirely by the preparation worker. The frame callback only signals a condition. */
private class CastLegacyBenchmarkSurface(private val input: Surface, private val width: Int, private val height: Int,
    private val painter: CastLegacyBenchmarkPainter) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var overlayTexture = 0
    private var videoProgram = 0
    private var overlayProgram = 0
    private var source: SurfaceTexture? = null
    private var output: Surface? = null
    val decoderSurface: Surface get() = checkNotNull(output)
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val coords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val frame = Object() // Java monitor methods are required for the SurfaceTexture callback rendezvous.
    private var available = false
    private var closed = false
    init {
        try {
        require(width.toLong() * height * 4 < Runtime.getRuntime().maxMemory() / 4) { "composition-memory-budget" }
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
        val names = IntArray(2); GLES20.glGenTextures(2, names, 0); texture = names[0]; overlayTexture = names[1]
        configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        configureTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        videoProgram = program(true); overlayProgram = program(false)
        source = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            // Explicit main Handler: a worker without a Looper must never wait for a callback on itself.
            setOnFrameAvailableListener({ synchronized(frame) { available = true; frame.notifyAll() } }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        output = Surface(checkNotNull(source))
        bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(checkNotNull(bitmap))
        } catch (failure: Throwable) { close(); throw failure }
    }
    private fun configureTexture(target: Int, name: Int) {
        GLES20.glBindTexture(target, name)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    fun draw(presentationUs: Long) {
        synchronized(frame) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!available) { require(System.nanoTime() < deadline) { "decoder-frame-timeout" }; frame.wait(100) }
            available = false
        }
        val source = checkNotNull(source)
        val bitmap = checkNotNull(bitmap)
        source.updateTexImage(); source.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, width, height); GLES20.glDisable(GLES20.GL_BLEND)
        drawTexture(videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture, matrix)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT); painter.draw(checkNotNull(canvas), presentationUs / 1000)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val overlayMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0); it[5] = -1f; it[13] = 1f }
        drawTexture(overlayProgram, GLES20.GL_TEXTURE_2D, overlayTexture, overlayMatrix)
        require(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "composition-failed" }
        EGLExt.eglPresentationTimeANDROID(display, window, presentationUs * 1000)
        require(EGL14.eglSwapBuffers(display, window)) { "encoder-surface-failed" }
    }
    private fun drawTexture(program: Int, target: Int, texture: Int, matrix: FloatArray) {
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition"); val uv = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(uv); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    private fun program(external: Boolean): Int {
        fun shader(type: Int, code: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code); GLES20.glCompileShader(it)
            val status = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            require(status[0] != 0) { "shader-compile-failed" }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uMatrix; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=(uMatrix*aTexCoord).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            (if (external) "#extension GL_OES_EGL_image_external : require\n" else "") +
                "precision mediump float; varying vec2 vTex; uniform ${if (external) "samplerExternalOES" else "sampler2D"} sTexture; void main(){gl_FragColor=texture2D(sTexture,vTex);}")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it)
            val status = IntArray(1); GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            require(status[0] != 0) { "shader-link-failed" }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        output?.release(); source?.release(); bitmap?.recycle(); canvas = null
        if (context != EGL14.EGL_NO_CONTEXT) {
            GLES20.glDeleteTextures(2, intArrayOf(texture, overlayTexture), 0)
            GLES20.glDeleteProgram(videoProgram); GLES20.glDeleteProgram(overlayProgram)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
        }
        EGL14.eglReleaseThread()
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        input.release()
    }
}
/** Draws into the encoded frames; DLNA receivers need no special subtitle or danmaku implementation. */
internal class CastLegacyBenchmarkPainter(comments: List<CastDanmaku>, width: Int, height: Int, style: CastDanmakuStyle) {
    private data class Entry(val value: CastDanmaku, val lane: Int, val width: Float, val lifetime: Long)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 25f * height / 720 * style.scale; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val line = paint.textSize * 1.3f
    private val width = width.toFloat()
    private val alpha = (style.opacity * 255).toInt()
    private val entries: List<Entry>
    init {
        val lanes = (height * style.coverage / line).toInt().coerceIn(1, 40)
        val next = Array(4) { LongArray(lanes) }
        entries = comments.mapNotNull { value ->
            val group = when (value.mode) { 4 -> 1; 5 -> 2; 6 -> 3; else -> 0 }
            val lane = next[group].indices.minByOrNull { next[group][it] } ?: return@mapNotNull null
            if (next[group][lane] > value.atMs) return@mapNotNull null
            val textWidth = paint.measureText(value.text)
            // Constant pixel speed keeps later short/long comments from catching earlier comments.
            val moving = group == 0 || group == 3
            val lifetime = if (moving) ((width + textWidth) / (width / 8000f)).toLong() else 4000
            next[group][lane] = value.atMs + if (moving) ((textWidth + line) / (width / 8000f)).toLong() else lifetime
            Entry(value, lane, textWidth, lifetime)
        }
    }
    fun draw(canvas: Canvas, timeMs: Long) {
        val first = entries.binarySearchBy((timeMs - 60_000).coerceAtLeast(0)) { it.value.atMs }.let { if (it < 0) -it - 1 else it }
        for (index in first until entries.size) {
            val entry = entries[index]
            val age = timeMs - entry.value.atMs
            if (age < 0) break
            if (age > entry.lifetime) continue
            val x = when (entry.value.mode) { 4, 5 -> (width - entry.width) / 2;
                6 -> -entry.width + age * width / 8000; else -> width - age * width / 8000 }
            val y = if (entry.value.mode == 4) canvas.height - line * entry.lane - line * .3f else line * (entry.lane + 1)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = paint.textSize * .1f; paint.color = Color.BLACK; paint.alpha = alpha
            canvas.drawText(entry.value.text, x, y, paint)
            paint.style = Paint.Style.FILL; paint.color = entry.value.color; paint.alpha = alpha
            canvas.drawText(entry.value.text, x, y, paint)
        }
    }
}
