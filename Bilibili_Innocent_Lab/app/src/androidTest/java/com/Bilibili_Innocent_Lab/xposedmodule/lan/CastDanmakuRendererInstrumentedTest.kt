package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Bundle
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameGl
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Offscreen GLES/Canvas regression, no network, host navigation, TV or media credentials. */
@RunWith(AndroidJUnit4::class)
class CastDanmakuRendererInstrumentedTest {
    private val width = 1280
    private val height = 532
    private val background = Color.rgb(32, 48, 64)
    private val style = CastDanmakuStyle()

    private fun withGl(action: () -> Unit) {
        val egl = checkNotNull(FrameGl.create(EGL14.EGL_PBUFFER_BIT))
        val surface = EGL14.eglCreatePbufferSurface(egl.display, egl.config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        try {
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(egl.display, surface, surface, egl.context))
            GLES20.glViewport(0, 0, width, height)
            action()
        } finally {
            EGL14.eglMakeCurrent(egl.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(egl.display, surface)
            FrameGl.release(egl)
            EGL14.eglTerminate(egl.display)
        }
    }

    private fun clear() {
        GLES20.glClearColor(32f / 255, 48f / 255, 64f / 255, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun compare(reference: CastDanmakuReferencePainter, time: Long, sample: String) {
        val expected = createBitmap(width, height)
        try {
            expected.eraseColor(background)
            reference.draw(Canvas(expected), time)
            val pixels = ByteBuffer.allocateDirect(width * height * 4)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            val baseline = IntArray(width * height)
            expected.getPixels(baseline, 0, width, 0, 0, width, height)
            val actual = IntArray(width * height)
            var totalError = 0L; var severe = 0; var ink = 0; var actualInk = 0; var unionInk = 0
            repeat(height) { y -> repeat(width) { x ->
                val color = baseline[y * width + x]
                val offset = ((height - 1 - y) * width + x) * 4
                val red = pixels.get(offset).toInt() and 255
                val green = pixels.get(offset + 1).toInt() and 255
                val blue = pixels.get(offset + 2).toInt() and 255
                actual[y * width + x] = Color.rgb(red, green, blue)
                val error = abs(red - Color.red(color)) + abs(green - Color.green(color)) + abs(blue - Color.blue(color))
                totalError += error
                if (error > 96) severe++
                val expectedHasInk = abs(Color.red(color) - 32) + abs(Color.green(color) - 48) + abs(Color.blue(color) - 64) > 12
                val actualHasInk = abs(red - 32) + abs(green - 48) + abs(blue - 64) > 12
                if (expectedHasInk) ink++
                if (actualHasInk) actualInk++
                if (expectedHasInk || actualHasInk) unionInk++
            } }
            capture(sample, expected, actual)
            assertTrue("reference contains visible text", ink > 100)
            // Canvas subpixel rasterization and GLES bilinear translation differ at antialiased edges.
            // Normalize to glyph pixels, not the mostly empty video frame: missing text must fail.
            val meanError = totalError.toDouble() / (unionInk * 3)
            val summary = "$sample ink=$ink actualInk=$actualInk union=$unionInk meanChannelError=$meanError severe=$severe"
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("castImage", summary) })
            assertTrue(summary, actualInk in (ink * .75).toInt()..(ink * 1.25).toInt())
            assertTrue(summary, meanError < 24)
            assertTrue(summary, severe < unionInk / 3)
        } finally { expected.recycle() }
    }

    private fun capture(sample: String, expected: Bitmap, actual: IntArray) {
        if (InstrumentationRegistry.getArguments().getString("castCapture") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "cast-benchmark")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$sample-canvas.png").outputStream().use { check(expected.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val rendered = createBitmap(width, height)
        try {
            rendered.setPixels(actual, 0, width, 0, 0, width, height)
            File(directory, "$sample-gles.png").outputStream().use { check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { rendered.recycle() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("castCapture", directory.absolutePath) })
    }

    @Test fun cachedSpritesMatchCanvasForChineseColorOpacityModesAndRewind() = withGl {
        val comments = listOf(
            CastDanmaku(0, 1, Color.WHITE, "滚动弹幕：中文、English 与数字 123"),
            CastDanmaku(0, 6, Color.CYAN, "反向弹幕 👋 café"),
            CastDanmaku(0, 4, Color.YELLOW, "底部弹幕"),
            CastDanmaku(0, 5, Color.RED, "顶部弹幕"))
        val metrics = CastPreparationMetrics()
        val reference = CastDanmakuReferencePainter(comments, width, height, style)
        CastDanmakuRenderer(comments, width, height, style, metrics).use { renderer ->
            for (time in listOf(2000L, 2347L, 3000L, 2000L)) {
                clear(); renderer.draw(time); compare(reference, time, "modes-$time")
            }
            val uploads = metrics.textUploads
            repeat(240) { clear(); renderer.draw(2000) }
            assertEquals("same text must not be re-uploaded each frame", uploads, metrics.textUploads)
            assertTrue(metrics.uploadedBytes < width.toLong() * height * 4)
        }
    }

    @Test fun longUnicodeTextAndAtlasReuseDoNotCorruptQueuedDraws() = withGl {
        val comments = (0..20).map { i ->
            CastDanmaku(i * 5000L, 5, Color.rgb(60 + i * 7, 210 - i * 5, 160), "跨纹理边界中文👋é${i} ".repeat(35))
        }
        val metrics = CastPreparationMetrics()
        val reference = CastDanmakuReferencePainter(comments, width, height, style)
        CastDanmakuRenderer(comments, width, height, style, metrics, maxAtlasSide = 256).use { renderer ->
            for (i in 0..20) {
                val time = i * 5000L + 2000
                clear(); renderer.draw(time); compare(reference, time, "atlas-$i")
            }
            assertTrue("exercise atlas reuse", metrics.atlasResets > 0)
            assertTrue(metrics.atlasBytes <= 256L * 256 * 4)
        }
    }

    @Test fun smallAndLargeStylesKeepOutlinedTextAndUnicodeReadable() = withGl {
        val comments = listOf(
            CastDanmaku(0, 1, Color.WHITE, "细节检查：清晰中文 123 ABC café"),
            CastDanmaku(0, 4, Color.CYAN, "底部：👋🏽 组合字符 e\u0301"),
            CastDanmaku(100, 5, Color.YELLOW, "描边与透明度"))
        for ((name, sampleStyle) in listOf("small" to CastDanmakuStyle(scale = .5f, opacity = .2f),
            "large" to CastDanmakuStyle(scale = 2f, opacity = 1f))) {
            val reference = CastDanmakuReferencePainter(comments, width, height, sampleStyle)
            CastDanmakuRenderer(comments, width, height, sampleStyle, CastPreparationMetrics()).use { renderer ->
                clear(); renderer.draw(2347); compare(reference, 2347, name)
            }
        }
    }
}
